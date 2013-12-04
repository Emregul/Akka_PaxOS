package com.paxos.server.original

import akka.actor.{ActorRef, ActorSystem, Props, Actor}
import scala.concurrent.duration._
import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory
import akka.kernel.Bootable
import java.net.InetSocketAddress
import scala.sys.process._
import scala.collection.mutable.ArrayBuffer
import akka.actor.actorRef2Scala

sealed abstract class Message
abstract class PaxosMessage extends Message
case class ProposeMessage(val ballotNumber:Int, val proposedValue:String, val logPosition:Int) extends PaxosMessage
case class PromiseMessage(val ballotNumber:Int, val proposedValue:String, val oldBallotNumber:Int, val oldValue:String, val logPosition:Int, val replicaID:Int) extends PaxosMessage
case class AcceptMessage(val ballotNumber:Int, val proposedValue:String, val logPosition:Int) extends PaxosMessage
case class AcceptAcknowledgementMessage(val ballotNumber:Int, val proposedValue:String, val logPosition:Int, val isSuccess:Boolean) extends PaxosMessage
case class WriteRequest(val acceptMessage:AcceptMessage) extends PaxosMessage
case class PaxosPromotionMessage(val blogPostList : ArrayBuffer[String]) extends PaxosMessage

abstract class CatchupMessage extends Message
case class catchupLogPosition(val logPosition:Int) extends CatchupMessage
case class catchupLogPositionResponse(val logPosition:Int, val filledValue:String, val isFilled:Boolean, val lastReceivedBAllotNum:Int) extends CatchupMessage

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
case class PaxosSuccess(val replicaID:Int, val blogPost: String) extends SystemMessage
case class PaxosReadyReject(val replicaId:Int) extends SystemMessage
case class ReadPostResponse(val posts:String , val replicaID:Int) extends SystemMessage
case class PaxosTimeout(val logPosition : Int, val blogPost : String) extends SystemMessage


class LogAcceptorState(var lastReceivedBallotNumber:Int = -1, var blogPost:String = "")
class LogWriteRequest(var ballotNumber:Int, var proposedValue:String, var logPosition:Int)

class ReplicaManagerApplication(val n: Int, val numberOfReplicas : Int) extends Bootable {
  //#setup
  val system = ActorSystem("ReplicaManager", ConfigFactory.load.getConfig("serveramazon" + n))
  val consoleActor = system.actorOf(Props[ReplicaSupervisor], name = "repliceSupervisorActor")
  val replicaActor = system.actorOf(Props(classOf[Replica],n, numberOfReplicas, consoleActor), "replica")
    
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


class Replica(ID:Int = -1, val numberOfReplicas:Int = -1, val toConsole: ActorRef) extends Actor{
  import context._
  var replicaRefs = Map[Int,ActorRef]()
   var isReplicaActive = true;
  
  // Proposer State and Queue
  var isProposedActive = false
  var ownWriteRequestList = new ArrayBuffer[String]()
  
  // Last selected Number States
  var lastSelectedBallotNumber:Int = ID
  var lastReceivedBallotNumber:Int = -1
  
  // StateList for the Acceptors
  var logAcceptorStateList= new ArrayBuffer[LogAcceptorState]
  
  // Proposer keeps promise senders lists
  var promiseMessageSenderList = new ArrayBuffer[ActorRef]
  
  // Main Stream
  val blogPosts = new ArrayBuffer[String]()
  
  // The Log Position Applications Race for
  var nextLogPosition: Int = 0
  
  // Create Map for Learners - key:logPosition value: logWriteRequest
  var learnerMap = new collection.mutable.HashMap[Int, ArrayBuffer[LogWriteRequest]]
  
  var timeoutTimer = system.scheduler.scheduleOnce(5000 milliseconds, self, PaxosTimeout)
  timeoutTimer.cancel();
  
   def receive = {
     case NotifySupervisor() => {
       println("Everybody up gogo!")
       for (i<- 1 to numberOfReplicas) {
         if (i != ID)
         {
           val address = ReplicaManager.serverMap(i)
           val remotePath = "akka.tcp://ReplicaManager@"+ address.getHostName+ ":" + address.getPort +"/user/replica"
           replicaRefs += (i->context.actorFor(remotePath))
         } else {
           replicaRefs += (i-> self)
         }
       }
       println(replicaRefs)
       //sender ! ReplicaReady(ID)
     }
    case Post(blogPost) => if(isReplicaActive) {
      ownWriteRequestList += blogPost
      if(!isProposedActive){
        startPaxos(nextLogPosition, blogPost);
        isProposedActive = true
      }
    }
    case Fail => isReplicaActive = false
    case UnFail => {if(!isReplicaActive) {isReplicaActive = true; catchupMissing}}
    case Read() => { if(isReplicaActive){ toConsole ! new ReadPostResponse(readBlogList, ID)}}
      
    // Paxos Messages
    case ProposeMessage(ballotNumber:Int, proposedValue:String, logPosition:Int) => if(isReplicaActive) receivedProposePaxos(ballotNumber, proposedValue, logPosition, sender)
    case PromiseMessage(ballotNumber:Int, proposedValue:String, oldBallotNumber:Int, oldValue:String, logPosition:Int, replicaID:Int) => if(isReplicaActive){ promiseMessageReceived(new PromiseMessage(ballotNumber,proposedValue, oldBallotNumber, oldValue, logPosition, replicaID), sender);}//println("received promise");}
    case AcceptMessage(ballotNumber:Int, proposedValue:String, logPosition:Int) => if(isReplicaActive) {acceptMessageReceived(new AcceptMessage(ballotNumber, proposedValue, logPosition), sender); }//println("received accept");}
    case AcceptAcknowledgementMessage(ballotNumber:Int, proposedValue:String, logPosition:Int, isSuccess:Boolean) => if(isReplicaActive) acceptAcknowledgementMessageReceived(new AcceptAcknowledgementMessage(ballotNumber, proposedValue, logPosition, isSuccess))
    case WriteRequest(acceptMessage:AcceptMessage) => if(isReplicaActive){recieveWriteRequest(acceptMessage);}
    case PaxosTimeout(logPosition, blogPost) => if(isReplicaActive) {startPaxos(logPosition, blogPost);}
    // Catchup Messages
    case catchupLogPosition(logPosition:Int) => if(isReplicaActive){
      if(logPosition < nextLogPosition) {
        sender ! new catchupLogPositionResponse(logPosition, blogPosts(logPosition), true, lastReceivedBallotNumber)
        }else{
         sender ! new catchupLogPositionResponse(logPosition, "", false, lastReceivedBallotNumber) 
        }
      }
    case catchupLogPositionResponse(logPosition:Int, filledValue:String, isFilled:Boolean, lastReceivedBalNum:Int) => if(isReplicaActive){
      if(lastReceivedBalNum > lastReceivedBallotNumber){lastReceivedBallotNumber = lastReceivedBalNum}
      if(isFilled){
        if(nextLogPosition == logPosition){
        	addPostToBlog(filledValue)
        	catchupMissing
        }
      }
     } 
   }
   
   // Learners receive write requests
  def recieveWriteRequest(acceptMessage:AcceptMessage){
    if(nextLogPosition <= acceptMessage.logPosition){
	      if(learnerMap.contains(acceptMessage.logPosition)){
	      var writeRequests = learnerMap(acceptMessage.logPosition)
	      writeRequests += new LogWriteRequest(acceptMessage.ballotNumber, acceptMessage.proposedValue, acceptMessage.logPosition)
	      var counter:Int = 0
	      for(req <- writeRequests){if(req.ballotNumber == acceptMessage.ballotNumber) counter +=1}
	      if(counter > (numberOfReplicas/2)){
	        timeoutTimer.cancel
	        addPostToBlog(acceptMessage.proposedValue)
	        learnerMap = learnerMap - acceptMessage.logPosition
	        
	        // Check whether learner is proposer or not
	        if((acceptMessage.ballotNumber - ID) % numberOfReplicas == 0){
	        	// Remove post from request list then check for queue
	          
	            println("SizeOfList: " + ownWriteRequestList.size + "\tBallot Number: " + acceptMessage.ballotNumber + "\tID: " + ID + "\tPost: " + acceptMessage.proposedValue)
	        	ownWriteRequestList.remove(0)
	        	if(ownWriteRequestList.size > 0){
	        		startPaxos(nextLogPosition, ownWriteRequestList(0));
	        		isProposedActive = true
	        	}else{
	        		isProposedActive = false
	        	} 
	        }else{
	            if(ownWriteRequestList.size > 0){
	        		startPaxos(nextLogPosition, ownWriteRequestList(0));
	        		isProposedActive = true
	        	}else{
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
      startPaxos(nextLogPosition, acceptAcknowledgementMessage.proposedValue)
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
  
  
  def receivedProposePaxos(ballotNumber:Int, proposedValue:String, logPosition:Int, sender:ActorRef){
    
    while(logAcceptorStateList.size < (logPosition+1))
      logAcceptorStateList += new LogAcceptorState
      
    if( ballotNumber > logAcceptorStateList(logPosition).lastReceivedBallotNumber ){
      val oldBulletNumber = logAcceptorStateList(logPosition).lastReceivedBallotNumber
      val oldValue = logAcceptorStateList(logPosition).blogPost
      lastReceivedBallotNumber = ballotNumber
      logAcceptorStateList(logPosition).lastReceivedBallotNumber = ballotNumber
      logAcceptorStateList(logPosition).blogPost = proposedValue
      sender ! new PromiseMessage(ballotNumber, proposedValue, oldBulletNumber, oldValue, logPosition, ID)
    }   
    
  }

  def readBlogList():String = {
	  var posts:String = ""
      for(blogPost <- blogPosts) 
    	  posts = posts + blogPost + ":"
	  return posts
  }
  
  def catchupMissing(){
    for( i <- 1 to numberOfReplicas){
      if(i != ID){replicaRefs(i) ! new catchupLogPosition(nextLogPosition)}
    }
  }
  
  def startPaxos(logPosition:Int, post:String){
    promiseMessageSenderList.clear
    // Send Propose Messages to All Acceptors
    val ballotNum = getNextAvailableBallotNumber
    //println("Paxos from replica ID: "  + ID + "\tbNum: " + ballotNum + "\tPost: " + post)
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
  
  // Add Blog to Stream
  def addPostToBlog(blog: String){
    blogPosts +=blog
    nextLogPosition += 1
  }  
   
}

class ReplicaSupervisor extends Actor {
  val valuelessRegExp = "([a-zA-Z]+)\\((\\d+)\\)".r
  val oneValueRegexp = "([a-zA-Z]+)\\((\\d+)\\)".r
  val twoValueStringRegexp = "([a-zA-Z]+)\\((\\d+),[ ]*\"([a-zA-Z ]+)\"\\)".r

  def receive = {
   case oneValueRegexp(call,arg1) => call match{
   case "post" => ReplicaManager.replicaApp.sendFromConsoleToActor(Post(arg1))
   case _ => print("Unknown Command\n")
   }
   case "fail" => ReplicaManager.replicaApp.sendFromConsoleToActor(Fail())
   case "unfail" => ReplicaManager.replicaApp.sendFromConsoleToActor(UnFail())
   case "read" => ReplicaManager.replicaApp.sendFromConsoleToActor(Read())
   case "Stop" => exit(0)
   case "Start" => ReplicaManager.replicaApp.sendFromConsoleToActor(NotifySupervisor())
   case "download" =>
   case "run" => 
   case ReadPostResponse(readBlogList, replicaID) => println(readBlogList)
   case _ => print("Unknown Command\n")
  }
  
}


object ReplicaManager {
  //val masterAddress = getAddressFromConfig("master")
  var serverMap = Map[Int,InetSocketAddress]()
  var numberOfReplicas = 0;
  var id = 0;
  lazy val replicaApp = new ReplicaManagerApplication(id, numberOfReplicas)
  def main(args: Array[String]) {
    numberOfReplicas = args(0).toInt
    for (i <- 1 to numberOfReplicas)
    {
      val config = ConfigFactory.load.getConfig("serveramazon"+i)
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





