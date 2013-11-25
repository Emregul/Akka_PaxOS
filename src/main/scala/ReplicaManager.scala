import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }
import scala.concurrent.duration._
import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory
import akka.actor.Identify
import akka.actor.ActorIdentity
import akka.kernel.Bootable
import akka.actor.ReceiveTimeout
import java.net.InetSocketAddress

sealed abstract class Message
sealed abstract class PaxosMessage extends Message
case class PrepareRequestMessage(val proposedValue:Int, val ballotNumber:Int) extends PaxosMessage
case class PromiseMessage(val proposedValue:Int, val oldBallotNumber:Int, val oldValue:Int) extends PaxosMessage
case class PrepareAcceptMessage(val proposedValue:Int, val ballotNumber:Int) extends PaxosMessage
case class AcceptMessage(val proposedValue:Int) extends PaxosMessage

sealed abstract class SystemMessage extends Message
case class NotifyMaster(val value:Int) extends SystemMessage
case class NotifySupervisor() extends SystemMessage
case class NotifyReplica() extends SystemMessage
case class StartPaxos(val value:Int) extends SystemMessage
case class Post(val blogPost: String) extends SystemMessage
case class Fail() extends SystemMessage
case class UnFail() extends SystemMessage
case class Read() extends SystemMessage

class ReplicaManagerApplication(val n: Int) extends Bootable {
  //#setup
  val system = ActorSystem("ReplicaManager", ConfigFactory.load.getConfig("server" + n))
  val remotePath = "akka.tcp://MasterApplication@"+ ReplicaManager.masterAddress.getHostName() + ":"+ ReplicaManager.masterAddress.getPort() + "/user/master"
  val actor = system.actorOf(Props(classOf[Supervisor], remotePath,n), "supervisor")
    
  //#setup

  def startup() {
  }

  def shutdown() {
    system.shutdown()
  }
}


class Supervisor(masterPath:String,n:Int) extends Actor{
  import context._
  context.setReceiveTimeout(3.seconds)
  sendIdentifyRequest()
  val myProposer = context.actorOf(Proposer.props(1,null))
  val myAcceptor = context.actorOf(Acceptor.props(null))
  val myListener = context.actorOf(Listener.props())
  var supervisorRefs = Map[Int,ActorRef]()
  
  def sendIdentifyRequest(): Unit =
    context.actorSelection(masterPath) ! Identify(masterPath)

  def receive = {
    case ActorIdentity(masterPath, Some(actor)) ⇒
      println("say what")
      context.setReceiveTimeout(Duration.Undefined)
      context.become(active(actor))
      actor ! new NotifyMaster(n)
    case ActorIdentity(masterPath, None) ⇒ println(s"Remote actor not availible: masterPath")
    case ReceiveTimeout              ⇒ sendIdentifyRequest()
   
  }
   def active(actor: ActorRef): Actor.Receive = {
    
     case NotifySupervisor() => {
       println("Everybody up gogo!")
       for (i<- 1 to 2) {
         val port = 2553+i
         if (i != n)
         {
           val address = ReplicaManager.serverMap(n)
           val remotePath = "akka.tcp://ReplicaManager@"+ address.getHostName+ ":" + address.getPort +"/user/supervisor"
           supervisorRefs += (n->context.actorFor(remotePath))
         }
		 println("Someone connected")
       }
       supervisorRefs.foreach{case (n,ref) => ref ! Post("Post the news we are all up!")}
       actor ! "master"
     }
     case Post(blogPost) => println(blogPost)
     case StartPaxos(blogPost) =>
     case Read() =>
     case Fail() =>
     case UnFail() =>
     case _ => println("nothing")
  }
}

object Proposer {
	def props(n: Int, acceptors : Array[ActorRef]) : Props = Props(classOf[Proposer],n,acceptors) 
}
class Proposer(var n:Int, val acceptors: Array[ActorRef]) extends Actor {
	def receive = {
	case StartPaxos(v) => acceptors.foreach(acceptor => acceptor ! PrepareRequestMessage(5,n))
	case PromiseMessage(proposedValue, oldBallotNumber, oldValue) =>
	case _ => println("none");
	}
}
object Acceptor {
	def props(listeners : Array[ActorRef]) : Props = Props(classOf[Acceptor],listeners) 
}
class Acceptor(val listeners : Array[ActorRef]) extends Actor {
	def receive = {
	case PrepareRequestMessage(v,b) => sender ! new PromiseMessage(0,0,0)
	case PrepareAcceptMessage(v,b) =>
	case _ => println("none");
	}
}
object Listener {
	def props() : Props = Props(classOf[Listener]) 
}
class Listener extends Actor {
	def receive = {
	case AcceptMessage(v) =>
	case _ => println("none");
	}
}

object ReplicaManager {
  val masterAddress = getAddressFromConfig("master")
  var serverMap = Map[Int,InetSocketAddress]()
  def main(args: Array[String]) {
    for (i <- 1 to 5)
    {
      val config = ConfigFactory.load.getConfig("server"+i)
      serverMap += (i -> getAddressFromConfig("server" + i))
    }
    val id = readInt
	val app = new ReplicaManagerApplication(id)
	println("Application Started")
  }
  
  def getAddressFromConfig( configName : String) : InetSocketAddress = {
    val config = ConfigFactory.load.getConfig(configName)
    val hostname = ConfigFactory.load.getConfig(configName).getString("akka.remote.netty.tcp.hostname")
    val port = ConfigFactory.load.getConfig(configName).getString("akka.remote.netty.tcp.port")
    new InetSocketAddress(hostname,port.toInt)
  }
}





