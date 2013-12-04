object test {;import org.scalaide.worksheet.runtime.library.WorksheetSupport._; def main(args: Array[String])=$execute{;$skip(57); 
  println("Welcome to the Scala worksheet");$skip(12); 
  val x = 5;System.out.println("""x  : Int = """ + $show(x ));$skip(39); 
	
	def test(y:Int, f:Int =>Int) = f(y);System.out.println("""test: (y: Int, f: Int => Int)Int""");$skip(20); val res$0 = 
	
	test(x,y => y*y);System.out.println("""res0: Int = """ + $show(res$0));$skip(39); 
  
  var a = Map[Int, (String, Int)]();System.out.println("""a  : scala.collection.immutable.Map[Int,(String, Int)] = """ + $show(a ));$skip(25); 
  
  a += (1->("yey",2));$skip(10); val res$1 = 
  a(1)._2;System.out.println("""res1: Int = """ + $show(res$1));$skip(30); 
  a += (1->("yey",a(1)._2+1));$skip(10); val res$2 = 
  a(1)._2;System.out.println("""res2: Int = """ + $show(res$2))}
  
}
