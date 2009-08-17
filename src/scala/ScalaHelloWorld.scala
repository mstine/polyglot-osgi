package eg.impl.scala

import eg.api.HelloWorld

class ScalaHelloWorld extends HelloWorld {
  def greet() = {
    println("Hello, World! (from " + this.getClass() + ")")
  }
}
