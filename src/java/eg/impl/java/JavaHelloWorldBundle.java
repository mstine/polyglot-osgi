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
package eg.impl.java;

import eg.api.HelloWorld;
import eg.osgi.helpers.HelloWorldBundle;

public class JavaHelloWorldBundle extends HelloWorldBundle {

	public HelloWorld getHelloWorld() {
		return new JavaHelloWorld();
	}

}
