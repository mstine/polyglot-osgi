package eg.impl.groovy16;

import eg.api.*;
import eg.osgi.helpers.*;

class Groovy16HelloWorldBundle extends HelloWorldBundle {

	HelloWorld getHelloWorld() { new Groovy16HelloWorld() }
  
}

