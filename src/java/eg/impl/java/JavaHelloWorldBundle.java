package eg.impl.java;

import eg.api.HelloWorld;
import eg.osgi.helpers.HelloWorldBundle;

public class JavaHelloWorldBundle extends HelloWorldBundle {

	public HelloWorld getHelloWorld() {
		return new JavaHelloWorld();
	}

}
