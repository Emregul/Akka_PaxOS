object test {
  println("Welcome to the Scala worksheet")       //> Welcome to the Scala worksheet
  val x = 5                                       //> x  : Int = 5
	
	def test(y:Int, f:Int =>Int) = f(y)       //> test: (y: Int, f: Int => Int)Int
	
	test(x,y => y*y)                          //> res0: Int = 25
  
  var a = Map[Int, (String, Int)]()               //> a  : scala.collection.immutable.Map[Int,(String, Int)] = Map()
  
  a += (1->("yey",2))
  a(1)._2                                         //> res1: Int = 2
  a += (1->("yey",a(1)._2+1))
  a(1)._2                                         //> res2: Int = 3
  
}