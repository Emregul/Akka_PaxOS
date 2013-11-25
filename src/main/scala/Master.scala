import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }
import scala.concurrent.duration._
import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory
import akka.actor.Identify
import akka.actor.ActorIdentity
import akka.kernel.Bootable
import akka.actor.ReceiveTimeout
import java.net.InetSocketAddress

class Master extends Bootable{

  val system = ActorSystem("MasterApplication", ConfigFactory.load.getConfig("master"))
  val actor = system.actorOf(Props(classOf[MasterActor]), "master")  
  
  def startup() {
  }

  def shutdown() {
    system.shutdown()
  }	
}
class MasterActor extends Actor {
	var numOfReplicasStarted = 0
	var supervisorRefs = new Array[ActorRef](2)
	import context._	
	def receive = {
	case NotifyMaster(n) => 
	  	  val port = 2553+n;
	  	  val address = Master.serverMap(n)
		  val remotePath = "akka.tcp://ReplicaManager@"+address.getHostName() + ":"+address.getPort()+"/user/supervisor"
		  println("Someone connected")
		  val test = context.actorFor(remotePath)
          supervisorRefs(numOfReplicasStarted) = context.actorFor(remotePath)
          numOfReplicasStarted += 1
          if (numOfReplicasStarted == 2)
             supervisorRefs.foreach(supervisor => supervisor ! NotifySupervisor())
	case _ => println("nothing lovely")
	}
	
}

class CommandLineInputActor extends Actor {
  
  val oneValueRegexp = "([a-zA-Z]+)\\((\\d+)\\)".r
  val twoValueStringRegexp = "([a-zA-Z]+)\\((\\d+),[ ]*\"([a-zA-Z ]+)\"\\)".r

  def receive = {
   case oneValueRegexp(call,arg1) => call match{
   case "read" => println("read Received: " + arg1)
   case "fail" => println("fail Received: " + arg1)
   case "unfail" => println("unfail Received: " + arg1)
   case _ => print("Unknown Command\n")
   }
   case twoValueStringRegexp(call,arg1,arg2) => call match{
   case "post" => println("post Received: " + arg1 + "\t" + arg2)
   case _ => print("Unknown Command\n")
  }
  //case "PaxosReady" => ReplicaManager.paxosReady
  case "Stop" => exit(0)
  case _ => print("Unknown Command\n")
  }
}

object Master {
  var serverMap = Map[Int,InetSocketAddress]()
  def main(args: Array[String]) {
    for (i <- 1 to 5)
    {
      serverMap += (i -> getAddressFromConfig("server"+i))
    }
    
    
    println(serverMap)
	val app = new Master
	println("Master Started")
  }
  
  def getAddressFromConfig( configName : String) : InetSocketAddress = {
    val config = ConfigFactory.load.getConfig(configName)
    val hostname = ConfigFactory.load.getConfig(configName).getString("akka.remote.netty.tcp.hostname")
    val port = ConfigFactory.load.getConfig(configName).getString("akka.remote.netty.tcp.port")
    new InetSocketAddress(hostname,port.toInt)
  }
}
