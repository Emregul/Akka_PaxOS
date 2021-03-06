package com.paxos.local.promotion

import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }
import scala.concurrent.duration._
import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory
import akka.actor.Identify
import akka.actor.ActorIdentity
import akka.kernel.Bootable
import akka.actor.ReceiveTimeout
import java.net.InetSocketAddress
import scala.collection.mutable.ArrayBuffer

class Master extends Bootable{

  val system = ActorSystem("MasterApplication", ConfigFactory.load.getConfig("master"))
  val actor = system.actorOf(Props(classOf[MasterActor],Master.numberOfReplicas), "master")  
  
  def sendFromConsoleToActor(message : Message) = actor ! message
  
  def startup() {
    val myActor = system.actorOf(Props[CommandLineInputActor], name = "commandLineInterfaceActor")
    while(true){
	  myActor ! readLine(">> ")
    }
  }

  def shutdown() {
    system.shutdown()
  }	
}
class MasterActor(val numberOfReplicas : Int) extends Actor {
	var numOfReplicasStarted = 0
	var numOfReplicasReady = 0
	var replicaRefs = new Array[ActorRef](numberOfReplicas)
	var replicaPaths = new ArrayBuffer[String]
	import context._	
	def receive = {
	case NotifyMaster(n) => {
	  	  val address = Master.serverMap(n)
		  val remotePath = "akka.tcp://ReplicaManager@"+address.getHostName() + ":"+address.getPort()+"/user/replica"
		  println("Someone connected: " + remotePath)
		  if(!replicaPaths.contains(remotePath)){
			  replicaPaths += remotePath
			  replicaRefs(n-1) = context.actorFor(remotePath)
              numOfReplicasStarted += 1
              if (numOfReplicasStarted == numberOfReplicas)
            	  replicaRefs.foreach(supervisor => supervisor ! NotifySupervisor())
		  }
	}
	case ReplicaReady(n) => {
	  numOfReplicasReady += 1
	  if (numOfReplicasReady == numberOfReplicas)
	    println("All replicas up!")
	}
    case m:Post => replicaRefs(m.n-1) ! m
    case m:Fail =>  replicaRefs(m.n-1) ! m
    case m:UnFail => replicaRefs(m.n-1) ! m
    case m:Read => replicaRefs(m.n-1) ! m
    case m:ReadPostResponse => println(m.posts)
    case m:PaxosSuccess => //println(m)
	case _ => println("nothing lovely")
	}
  //def paxosReady(){replicaRefs.foreach{replica => replica ! (new PaxosReady(replicaList)) }}
	
}

class CommandLineInputActor extends Actor {
  
  val oneValueRegexp = "([a-zA-Z]+)\\((\\d+)\\)".r
  val twoValueStringRegexp = "([a-zA-Z]+)\\((\\d+),[ ]*\"([a-zA-Z ]+)\"\\)".r

  def receive = {
   case oneValueRegexp(call,arg1) => call match{
   case "read" => Master.app.sendFromConsoleToActor(Read(arg1.toInt))
   case "fail" => Master.app.sendFromConsoleToActor(Fail(arg1.toInt))
   case "unfail" => Master.app.sendFromConsoleToActor(UnFail(arg1.toInt))
   case _ => print("Unknown Command\n")
   }
   case twoValueStringRegexp(call,arg1,arg2) => call match{
   case "post" => Master.app.sendFromConsoleToActor(Post(arg1.toInt,arg2))
   case _ => print("Unknown Command\n")
  }
   case "Stop" => exit(0)
   case "download" =>
   case "run" =>
   case _ => print("Unknown Command\n")
  }
}

object Master {
  var serverMap = Map[Int,InetSocketAddress]()
  var numberOfReplicas = 0;
  lazy val app = new Master
  
  def main(args: Array[String]) {
    numberOfReplicas = args(0).toInt;
    for (i <- 1 to numberOfReplicas)
    {
      serverMap += (i -> getAddressFromConfig("server"+i))
    }
    println(serverMap)
	app.startup()
	println("Master Started")
	
  }
  
  def getAddressFromConfig( configName : String) : InetSocketAddress = {
    val config = ConfigFactory.load.getConfig(configName)
    val hostname = ConfigFactory.load.getConfig(configName).getString("akka.remote.netty.tcp.hostname")
    val port = ConfigFactory.load.getConfig(configName).getString("akka.remote.netty.tcp.port")
    new InetSocketAddress(hostname,port.toInt)
  }
}
