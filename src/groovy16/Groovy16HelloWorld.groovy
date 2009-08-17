package eg.impl.groovy16

import eg.api.*

class Groovy16HelloWorld implements HelloWorld {

	void greet() { 
		println "Hello, World! (" + this.class + ")"
	}

}
