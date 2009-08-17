package eg.impl.java;

import eg.api.*;

public class JavaHelloWorld implements HelloWorld {

	public void greet() {
		System.out.println("Hello, World! (" + this.getClass() + ")");
	}

}
