
package sg.edu.iss.bead.examples.morefunctions

import scala.collection.Seq

object PureandNonPureFunction {
  def pureFunc(cityName: String) = s"I live in $cityName"
  def notpureFunc(cityName: String) = println(s"I live in $cityName")
  def pureMul(x: Int, y: Int) = x * y
  def notpureMul(x: Int, y: Int) = println(x * y)    
  def main(args: Array[String]) {
    //Now call all the methods with some real values
    pureFunc("Singapore") //Does not print anything
    notpureFunc("Chennai") //Prints I live in Chennai
    pureMul(10, 25) //Again does not print anything
    notpureMul(10, 25) // Prints the multiplicaiton -i.e. 250     
    //Now call pureMil method in a different way
    val data = Seq.range(1,10).reduce(pureMul)
    println(s"My sequence is: " + data)
  }
}