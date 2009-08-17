package eg.impl.scala

import eg.api.HelloWorld
import eg.osgi.helpers.HelloWorldBundle

class ScalaHelloWorldBundle extends HelloWorldBundle {
	
	def getHelloWorld() = { new ScalaHelloWorld() }

}
