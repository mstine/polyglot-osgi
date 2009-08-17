package eg.impl.groovy17

import eg.api.*

class Groovy17HelloWorld implements HelloWorld {

	void greet() { 
		println "Hello, World! (" + this.class + ")"
	}

}
