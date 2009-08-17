/*
#This file is part of Polyglot-OSGi.
#
#Polyglot-OSGi is free software: you can redistribute it and/or modify
#it under the terms of the GNU General Public License as published by
#the Free Software Foundation, either version 3 of the License, or
#(at your option) any later version.
#
#Polyglot-OSGi is distributed in the hope that it will be useful,
#but WITHOUT ANY WARRANTY; without even the implied warranty of
#MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#GNU General Public License for more details.
#
#You should have received a copy of the GNU General Public License
#along with Polyglot-OSGi.  If not, see <http://www.gnu.org/licenses/>.
*/
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
