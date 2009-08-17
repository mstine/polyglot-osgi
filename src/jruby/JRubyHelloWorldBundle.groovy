package eg.impl.jruby

import org.jruby.embed.jsr223.JRubyScriptEngineManager
import eg.api.HelloWorld
import eg.osgi.helpers.HelloWorldBundle

class JRubyHelloWorldBundle extends HelloWorldBundle {

	HelloWorld getHelloWorld() {
    def engineMgr = new JRubyScriptEngineManager()
		assert engineMgr
    def engine = engineMgr.getEngineByName("jruby")
		assert engine
		return engine.eval("""
			include Java

			class JRubyHelloWorld
				include Java::eg.api.HelloWorld
		
				def greet
					puts "Hello, World! (From #{self.class})"
				end
			end
			
			JRubyHelloWorld.new
		""")
	}

}
