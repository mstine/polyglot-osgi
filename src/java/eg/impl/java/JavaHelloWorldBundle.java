package eg.impl.java;

import eg.api.*;
import eg.api.osgi.helpers.*;

public class JavaHelloWorldBundle extends HelloWorldBundle {

	public HelloWorld getHelloWorld() {
		return new JavaHelloWorld();
	}

}
