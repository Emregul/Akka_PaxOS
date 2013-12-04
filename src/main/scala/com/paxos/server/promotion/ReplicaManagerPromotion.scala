package com.paxos.server.promotion

import akka.actor.{ActorRef, ActorSystem, Props, Actor}
import scala.concurrent.duration._
import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory
import akka.actor.Identify
import akka.actor.ActorIdentity
import akka.kernel.Bootable
import akka.actor.ReceiveTimeout
import java.net.InetSocketAddress
import scala.sys.process._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.Duration
import akka.actor.ActorSelection.toScala
import akka.actor.actorRef2Scala


sealed abstract class Message
abstract class PaxosMessage extends Message
case class ProposeMessage(val ballotNumber:Int, val proposedValue:BPost, val logPosition:Int) extends PaxosMessage
case class PromiseMessage(val ballotNumber:Int, val proposedValue:BPost, val oldBallotNumber:Int, val oldValue:Option[BPost], val logPosition:Int, val replicaID:Int) extends PaxosMessage
case class AcceptMessage(val ballotNumber:Int, val proposedValue:BPost, val logPosition:Int) extends PaxosMessage
case class AcceptAcknowledgementMessage(val ballotNumber:Int, val proposedValue:BPost, val logPosition:Int, val isSuccess:Boolean) extends PaxosMessage
case class WriteRequest(val acceptMessage:AcceptMessage) extends PaxosMessage
case class PaxosPromotionMessage(val blogPostList : ArrayBuffer[BPost], val logPosition:Int) extends PaxosMessage

abstract class CatchupMessage extends Message
case class catchupLogPosition(val logPosition:Int) extends CatchupMessage
case class catchupLogPositionResponse(val logPosition:Int, val filledValue:Option[BPost], val isFilled:Boolean, val lastReceivedBAllotNum:Int) extends CatchupMessage

sealed abstract class SystemMessage extends Message
case class NotifyMaster(val value:Int) extends SystemMessage
case class NotifySupervisor() extends SystemMessage
case class NotifyReplica() extends SystemMessage
case class StartPaxos(val value:Int) extends SystemMessage
case class Post(val blogPost: String) extends SystemMessage
case class Fail() extends SystemMessage
case class UnFail() extends SystemMessage
case class Read() extends SystemMessage
case class ReplicaReady(val n: Int) extends SystemMessage
case class PaxosReadyConfirm(val replicaID:Int) extends SystemMessage
case class PaxosSuccess(val replicaID:Int, val blogPost: BPost) extends SystemMessage
case class PaxosReadyReject(val replicaId:Int) extends SystemMessage
case class ReadPostResponse(val posts:String , val replicaID:Int) extends SystemMessage
case class PaxosTimeout(val logPosition : Int, val blogPost : BPost) extends SystemMessage


class LogAcceptorState(var lastReceivedBallotNumber:Int = -1, var blogPost:Option[BPost] = None)
class LogWriteRequest(var ballotNumber:Int, var proposedValue:BPost, var logPosition:Int)


class BPost(val post:String, val ID:Int, val timeStamp:Long, var isAccepted:Boolean) extends Serializable

class ReplicaManagerApplicationPromotion(val n: Int, val numberOfReplicas : Int) extends Bootable {
  //#setup
  val system = ActorSystem("ReplicaManager", ConfigFactory.load.getConfig("serveramazon" + n))
  val consoleActor = system.actorOf(Props[ReplicaSupervisor], name = "repliceSupervisorActor")
  val replicaActor = system.actorOf(Props(classOf[ReplicaPromotion],n, numberOfReplicas, consoleActor), "replica")
    
  //#setup

  def startup() {
    
    while(true){
          consoleActor ! readLine(">> ")
    }
  }
  
  def sendFromConsoleToActor(message : Message) = replicaActor ! message
  
  def shutdown() {
    system.shutdown()
  }
}

class ReplicaPromotion(ID:Int = -1, val numberOfReplicas:Int = -1, val toConsole: ActorRef) extends Actor{
  import context._
  var replicaRefs = Map[Int,ActorRef]()
   var isReplicaActive = true;
  
  // Proposer State and Queue
  var isProposedActive = false
  var ownWriteRequestList = new ArrayBuffer[BPost]
  
  // Promotion Preferences
  var waitingWritesCount:Int = 0
  
  // Last selected Number States
  var lastSelectedBallotNumber:Int = ID
  var lastReceivedBallotNumber:Int = -1
  
  // StateList for the Acceptors
  var logAcceptorStateList= new ArrayBuffer[LogAcceptorState]
  
  // Proposer keeps promise senders lists
  var promiseMessageSenderList = new ArrayBuffer[ActorRef]
  
  // Main Stream
  //val blogPosts = new ArrayBuffer[BPost]()
  var blogPosts = new collection.mutable.HashMap[Int, BPost]
  
  // The Log Position Applications Race for
  var nextLogPositionForCatchUp: Int = 1
  
  // Create Map for Learners - key:logPosition value: logWriteRequest
  var learnerMap = new collection.mutable.HashMap[Int, ArrayBuffer[LogWriteRequest]]
  
  var timeoutTimer = system.scheduler.scheduleOnce(5000 milliseconds, self, PaxosTimeout)
  timeoutTimer.cancel();
  
  // Global Stream
  var globalMap = new collection.mutable.HashMap[Int, (BPost, Int)]
  
  // Catch up State or not
  var inCatchupState = false
  

   def receive = {
     case NotifySupervisor() => {
       println("Everybody up gogo!")
       for (i<- 1 to numberOfReplicas) {
         if (i != ID)
         {
           val address = ReplicaManagerPromotion.serverMap(i)
           val remotePath = "akka.tcp://ReplicaManager@"+ address.getHostName+ ":" + address.getPort +"/user/replica"
           replicaRefs += (i->context.actorFor(remotePath))
         } else {
           replicaRefs += (i-> self)
         }
       }
      // println(replicaRefs)
       //actor ! ReplicaReady(ID)
     }
    case Post(blogPost) => if(isReplicaActive) {
      val bPost = new BPost(blogPost, ID, System.currentTimeMillis(), false)
      ownWriteRequestList += bPost
      addPostToBlog(bPost)
      if(!isProposedActive){
        //nextLogPosition = getNextLogPosition
        startPaxos(getNextLogPosition, bPost);
        isProposedActive = true
      }
    }
    case Fail() => isReplicaActive = false
    case UnFail() => {if(!isReplicaActive) {isReplicaActive = true; catchupMissing}}
    case Read() => { if(isReplicaActive){ toConsole ! new ReadPostResponse(readBlogList, ID)}}
      
    // Paxos Messages
    case ProposeMessage(ballotNumber:Int, proposedValue:BPost, logPosition:Int) => if(isReplicaActive) receivedProposePaxos(ballotNumber, proposedValue, logPosition, sender)
    case m:PromiseMessage => if(isReplicaActive){ promiseMessageReceived(m, sender);}
    case m:AcceptMessage => if(isReplicaActive) {acceptMessageReceived(m, sender); }
    case m:AcceptAcknowledgementMessage => if(isReplicaActive) acceptAcknowledgementMessageReceived(m)
    case WriteRequest(acceptMessage:AcceptMessage) => if(isReplicaActive){recieveWriteRequest(acceptMessage); }
    case PaxosTimeout(logPosition, blogPost) => if(isReplicaActive) {startPaxos(getNextLogPosition, blogPost);}
    // Paxos Promotion Message
    case PaxosPromotionMessage(blogPost, logPosition:Int) => (if(isReplicaActive)addPromotionPostsToBlog(logPosition, blogPost))
    // Catchup Messages
    case catchupLogPosition(logPosition:Int) => if(isReplicaActive){
      
      if(globalMap.contains(logPosition))
        sender ! new catchupLogPositionResponse(logPosition, Some(globalMap(logPosition)._1), true, lastReceivedBallotNumber)
      else
        sender ! new catchupLogPositionResponse(logPosition, None, false, lastReceivedBallotNumber)
      }
    case catchupLogPositionResponse(logPosition:Int, filledValue:Option[BPost], isFilled:Boolean, lastReceivedBalNum) => if(isReplicaActive){
      if(lastReceivedBalNum > lastReceivedBallotNumber){lastReceivedBallotNumber = lastReceivedBalNum}
      if(isFilled){
        addBlogToSpecificLogPosition(logPosition, filledValue.get)
      }
     } 
   }
   
   // Learners receive write requests
  def recieveWriteRequest(acceptMessage:AcceptMessage){
    if(getNextLogPosition <= acceptMessage.logPosition){
            if(learnerMap.contains(acceptMessage.logPosition)){
              var writeRequests = learnerMap(acceptMessage.logPosition)
              writeRequests += new LogWriteRequest(acceptMessage.ballotNumber, acceptMessage.proposedValue, acceptMessage.logPosition)
              var counter:Int = 0
              for(req <- writeRequests){if(req.ballotNumber == acceptMessage.ballotNumber) counter +=1}
              if(counter > (numberOfReplicas/2)){
                timeoutTimer.cancel
                learnerMap = learnerMap - acceptMessage.logPosition
                
                // Check whether learner is proposer or not
                if((acceptMessage.ballotNumber - ID) % numberOfReplicas == 0){
                        // Remove post from request list then check for queue
                  var sizeofWaitingList:Int = ownWriteRequestList.size
                  
                  // Make written values accepted for promotion
                        for(i <- 0 until sizeofWaitingList){
                          for(j <- 1 to blogPosts.size){
                            if(ownWriteRequestList(i).timeStamp == blogPosts(j).timeStamp){
                              blogPosts(j).isAccepted = true
                            }
                          }
                        }
                  
                        /*ownWriteRequestList.remove(0)
                        sizeofWaitingList =  ownWriteRequestList.size*/
                        if(sizeofWaitingList > 0){
                                //Write and Promote complete list rather than starting Paxos        
                                var count  = 0;
                                var promoteList = new ArrayBuffer[BPost]
                                while(count < sizeofWaitingList){
                                    var post = ownWriteRequestList(0)
                                    post.isAccepted = true
                                        promoteList +=post
                                        globalMap(acceptMessage.logPosition + count ) = (post, 1)
                                        ownWriteRequestList.remove(0)
                                        count += 1
                                }

                                for( i <- 1 to numberOfReplicas){
                                        if(i != ID){replicaRefs(i) ! new PaxosPromotionMessage(promoteList, acceptMessage.logPosition)}
                                }

                                // If there are new post request after write acceptance start a new paxos for the next one
                                if(ownWriteRequestList.size > 0){
                                        startPaxos(getNextLogPosition, ownWriteRequestList(0));
                                        isProposedActive = true
                                }
                                else{ 
                                        isProposedActive = false
                                }

                        }else{
                                isProposedActive = false
                        } 
                }else{ // Learner is not proposer
                         //addPostToBlog(acceptMessage.proposedValue)
                         //println("ID: " + ID  + "\tSizeOwners: " +  ownWriteRequestList.size)
                        // If there are new post request after write acceptance start a new paxos for the next one
                        if(ownWriteRequestList.size > 0){
                                startPaxos(getNextLogPosition, ownWriteRequestList(0));
                                isProposedActive = true
                        }
                        else{ 
                                isProposedActive = false
                        }
                }
              }
            }
            else{
              var writeRequests = new ArrayBuffer[LogWriteRequest]
              writeRequests += new LogWriteRequest(acceptMessage.ballotNumber, acceptMessage.proposedValue, acceptMessage.logPosition)
              learnerMap(acceptMessage.logPosition) = writeRequests
            }
    }
  }
  
  // 
  def acceptAcknowledgementMessageReceived(acceptAcknowledgementMessage:AcceptAcknowledgementMessage){
    if(!acceptAcknowledgementMessage.isSuccess){
      startPaxos(getNextLogPosition, acceptAcknowledgementMessage.proposedValue)
    }
  }
  def acceptMessageReceived(message:AcceptMessage, sender:ActorRef){
    
    if(logAcceptorStateList(message.logPosition).lastReceivedBallotNumber <= message.ballotNumber){
      // Send A Message to Learner
      replicaRefs.foreach{case (n,replica) => replica ! new WriteRequest(message)}
      sender ! new AcceptAcknowledgementMessage(message.ballotNumber, message.proposedValue, message.logPosition, true)
    }
    else sender ! new AcceptAcknowledgementMessage(message.ballotNumber, message.proposedValue, message.logPosition, false)
    
  }
  
  def promiseMessageReceived(message:PromiseMessage, sender:ActorRef){
    //println("Promise message is received from replica " + message.replicaID + " for post " + message.proposedValue);
    promiseMessageSenderList += sender
    if(promiseMessageSenderList.size > (numberOfReplicas/2)){
      for(acceptor <- promiseMessageSenderList){
        // Send Write Request
        acceptor ! new AcceptMessage(message.ballotNumber, message.proposedValue, message.logPosition)
      }
    }
  }
  
  
  def receivedProposePaxos(ballotNumber:Int, proposedValue:BPost, logPosition:Int, sender:ActorRef){
    
    while(logAcceptorStateList.size < (logPosition+1))
      logAcceptorStateList += new LogAcceptorState
      
    if( ballotNumber > logAcceptorStateList(logPosition).lastReceivedBallotNumber ){
      val oldBulletNumber = logAcceptorStateList(logPosition).lastReceivedBallotNumber
      val oldValue = logAcceptorStateList(logPosition).blogPost
      lastReceivedBallotNumber = ballotNumber
      logAcceptorStateList(logPosition).lastReceivedBallotNumber = ballotNumber
      logAcceptorStateList(logPosition).blogPost = Some(proposedValue)
      sender ! new PromiseMessage(ballotNumber, proposedValue, oldBulletNumber, oldValue, logPosition, ID)
    }   
  }

  def readBlogList():String = {
          var posts:String = ""
            
          // Assume there is no gap in the log  
      for(i <- 1 to blogPosts.size) 
              posts = posts + blogPosts(i).post + ":"
          return posts
  }
  
  def catchupMissing(){
    if(!blogPosts.contains(nextLogPositionForCatchUp)){
      for( i <- 1 to numberOfReplicas){
              //println("NextLogPos: " + nextLogPosition)
              if(i != ID){replicaRefs(i) ! new catchupLogPosition(nextLogPositionForCatchUp)}
            }
    }else{
      nextLogPositionForCatchUp +=1
      catchupMissing
    }
  }
  
  def startPaxos(logPosition:Int, post:BPost){
    promiseMessageSenderList.clear
    // Send Propose Messages to All Acceptors
    val ballotNum = getNextAvailableBallotNumber
    replicaRefs.foreach{case (n,replica) => replica ! new ProposeMessage(ballotNum, post, logPosition)}
    timeoutTimer = system.scheduler.scheduleOnce(5000 milliseconds, self, PaxosTimeout(logPosition, post))
  }
  
  // Returns the next available ballot number for paxos
  def getNextAvailableBallotNumber():Int = {
          if(lastSelectedBallotNumber >= lastReceivedBallotNumber){
            lastSelectedBallotNumber += numberOfReplicas
            return lastSelectedBallotNumber
          }
          else{
            val divident:Int = lastReceivedBallotNumber / numberOfReplicas
            return (divident+1) * numberOfReplicas + (ID)
          }
  }
  
  def addBlogToSpecificLogPosition(logPos:Int, blog: BPost){
    if(!blogPosts.contains(logPos)){
     blogPosts(logPos) = blog
     nextLogPositionForCatchUp += 1
     catchupMissing
          }
  }
  
  // Add Blog to Stream
  def addPostToBlog(blog: BPost){
    blogPosts(blogPosts.size + 1) = blog
    nextLogPositionForCatchUp
  }  
  
  def addPromotionPostsToBlog(logPos:Int, blogs: ArrayBuffer[BPost]){
        val isGapExist = (logPos - nextLogPositionForCatchUp) > 0  
    blogPosts.get(logPos) match{
      case Some(bPos) => {
          val size = blogPosts.size
                    for(i <- 1 to blogs.size){
                      blogPosts(i + size) = blogs(i -1)
                    }
                nextLogPositionForCatchUp += blogs.size
      }
      case None => {
         for(i <- 0 until blogs.size){
              blogPosts(i + logPos) = blogs(i)
             }
         if(!isGapExist){nextLogPositionForCatchUp += blogs.size}
      }
    }
    
    for( i <- 0 until blogs.size){
      globalMap(logPos + i) = (blogs(i), 1)
    }
  }
  
  def getNextLogPosition():Int = {
    return blogPosts.filter(post => post._2.isAccepted == true).size + 1
  }
}
class ReplicaSupervisor extends Actor {
  val oneValueRegexp = "([a-zA-Z]+)\\(\"([a-zA-Z ]+)\"\\)".r
  val twoValueStringRegexp = "([a-zA-Z]+)\\((\\d+),[ ]*\"([a-zA-Z ]+)\"\\)".r

  def receive = {
   case oneValueRegexp(call,arg1) => call match{
   case "post" => ReplicaManagerPromotion.replicaApp.sendFromConsoleToActor(Post(arg1))
   case _ => print("Unknown Command\n")
   }
   case "fail" => ReplicaManagerPromotion.replicaApp.sendFromConsoleToActor(Fail())
   case "unfail" => ReplicaManagerPromotion.replicaApp.sendFromConsoleToActor(UnFail())
   case "read" => ReplicaManagerPromotion.replicaApp.sendFromConsoleToActor(Read())
   case "Stop" => exit(0)
   case "Start" => ReplicaManagerPromotion.replicaApp.sendFromConsoleToActor(NotifySupervisor())
   case "download" =>
   case "run" => 
   case ReadPostResponse(readBlogList, replicaID) => println(readBlogList)
   case _ => print("Unknown Command\n")
  }
  
}


object ReplicaManagerPromotion {
  //val masterAddress = getAddressFromConfig("master")
  var serverMap = Map[Int,InetSocketAddress]()
  var numberOfReplicas = 0;
  var id = 0;
  lazy val replicaApp = new ReplicaManagerApplicationPromotion(id, numberOfReplicas)
  def main(args: Array[String]) {
    numberOfReplicas = args(0).toInt
    for (i <- 1 to numberOfReplicas)
    {
      serverMap += (i -> getAddressFromConfig("serveramazon" + i))
    }
    id = args(1).toInt
    replicaApp.startup()
        println("Application Started")
  }
  
  def getAddressFromConfig( configName : String) : InetSocketAddress = {
    val config = ConfigFactory.load.getConfig(configName)
    val hostname = ConfigFactory.load.getConfig(configName).getString("akka.remote.netty.tcp.hostname")
    val port = ConfigFactory.load.getConfig(configName).getString("akka.remote.netty.tcp.port")
    new InetSocketAddress(hostname,port.toInt)
  }
}