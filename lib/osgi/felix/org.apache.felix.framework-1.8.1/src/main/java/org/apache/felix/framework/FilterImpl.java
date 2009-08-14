/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.framework;

import java.io.CharArrayReader;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.*;

import org.apache.felix.framework.util.StringMap;
import org.apache.felix.framework.util.ldap.*;
import org.osgi.framework.*;

/**
 * This class implements an RFC 1960-based filter. The syntax of the
 * filter string is the string representation of LDAP search filters
 * as defined in RFC 1960. These filters are used to search for services
 * and to track services using <tt>ServiceTracker</tt> objects.
**/
public class FilterImpl implements Filter
{
    private static final WeakHashMap m_programCache = new WeakHashMap();
    private final ThreadLocal m_cache = new ThreadLocal();
    private final Logger m_logger;
    private final Object[] m_program;
    private volatile String m_toString;

// TODO: FilterImpl needs a logger, this is a hack for FrameworkUtil.
    public FilterImpl(String expr) throws InvalidSyntaxException
    {
        this(null, expr);
    }

    /**
     * Construct a filter for a given filter expression string.
     * @param expr the filter expression string for the filter.
    **/
    public FilterImpl(Logger logger, String expr) throws InvalidSyntaxException
    {
        m_logger = logger;
        if (expr == null)
        {
            throw new NullPointerException("Filter cannot be null");
        }
        Object[] program = null;
        synchronized (m_programCache)
        {
            program = (Object[]) m_programCache.get(expr);
        }
        if (program == null)
        {
            CharArrayReader car = new CharArrayReader(expr.toCharArray());
            LdapLexer lexer = new LdapLexer(car);
            Parser parser = new Parser(lexer);
            try
            {
                if (!parser.start())
                {
                    throw new InvalidSyntaxException(
                        "Failed to parse LDAP query.", expr);
                }
            }
            catch (ParseException ex)
            {
                throw new InvalidSyntaxException(
                   ex.getMessage(), expr);
            }
            catch (IOException ex)
            {
                throw new InvalidSyntaxException(
                    ex.getMessage(), expr);
            }
            program = parser.getProgram();
            synchronized (m_programCache)
            {
                if (!m_programCache.containsKey(expr))
                {
                    m_programCache.put(expr, program);
                }
            }
        }
        m_program = program;
    }

    /**
     * Compares the <tt>Filter</tt> object to another.
     * @param o the object to compare this <tt>Filter</tt> against.
     * @return If the other object is a <tt>Filter</tt> object, it
     *         returns <tt>this.toString().equals(obj.toString())</tt>;
     *         <tt>false</tt> otherwise.
    **/
    public boolean equals(Object o)
    {
        if (o == null)
        {
            return false;
        }
        else if (o instanceof Filter)
        {
            return toString().equals(o.toString());
        }
        return false;
    }

    /**
     * Returns the hash code for the <tt>Filter</tt> object.
     * @return The value <tt>this.toString().hashCode()</tt>.
    **/
    public int hashCode()
    {
        return toString().hashCode();
    }

    private boolean match(Dictionary dict, ServiceReference ref, boolean caseSensitive)
        throws IllegalArgumentException
    {
        SoftReference tupleRef = (SoftReference) m_cache.get();
        Evaluator evaluator = null;
        SimpleMapper mapper = null;
        Object[] tuple = null;

        if (tupleRef != null)
        {
            tuple = (Object[]) tupleRef.get();
        }

        if (tuple == null)
        {
            evaluator = new Evaluator(m_program);
            mapper = new SimpleMapper();
        }
        else
        {
            evaluator = (Evaluator) tuple[0];
            mapper = (SimpleMapper) tuple[1];
            m_cache.set(null);
        }

        try
        {
            if (dict != null)
            {
                mapper.setSource(dict, caseSensitive);
            }
            else
            {
                mapper.setSource(ref);
            }

            return evaluator.evaluate(mapper);
        }
        catch (AttributeNotFoundException ex)
        {
            log(Logger.LOG_DEBUG, "FilterImpl: Attribute not found.", ex);
        }
        catch (EvaluationException ex)
        {
            log(Logger.LOG_ERROR, "FilterImpl: " + toString(), ex);
        }
        finally
        {
            if (dict != null)
            {
                mapper.setSource(null, caseSensitive);
            }
            else
            {
                mapper.setSource(null);
            }

            if (tuple == null)
            {
                m_cache.set(new SoftReference(new Object[] {evaluator, mapper}));
            }
            else
            {
                m_cache.set(tupleRef);
            }
        }

        return false;
    }

    /**
     * Filter using a <tt>Dictionary</tt> object. The <tt>Filter</tt>
     * is executed using the <tt>Dictionary</tt> object's keys and values.
     * @param dict the <tt>Dictionary</tt> object whose keys and values
     *             are used to determine a match.
     * @return <tt>true</tt> if the <tt>Dictionary</tt> object's keys
     *         and values match this filter; <tt>false</tt> otherwise.
     * @throws IllegalArgumentException if the dictionary contains case
     *         variants of the same key name.
    **/
    public boolean match(Dictionary dict)
        throws IllegalArgumentException
    {
        return match(dict, null, false);
    }

    /**
     * Filter using a service's properties. The <tt>Filter</tt>
     * is executed using the properties of the referenced service.
     * @param ref A reference to the service whose properties
     *             are used to determine a match.
     * @return <tt>true</tt> if the service's properties match this
     *         filter; <tt>false</tt> otherwise.
    **/
    public boolean match(ServiceReference ref)
    {
        return match(null, ref, false);
    }

    public boolean matchCase(Dictionary dict)
    {
        return match(dict, null, true);
    }

    /**
     * Returns the <tt>Filter</tt> object's filter string.
     * @return Filter string.
    **/
    public String toString()
    {
        if (m_toString == null)
        {
            m_toString = new Evaluator(m_program).toStringInfix();
        }
        return m_toString;
    }

    private void log(int flag, String msg, Throwable th)
    {
        if (m_logger == null)
        {
            System.out.println(msg + ": " + th);
        }
        else
        {
            m_logger.log(flag, msg, th);
        }
    }

    static class SimpleMapper implements Mapper
    {
        private ServiceReference m_ref = null;
        private StringMap m_map = null;

        public void setSource(ServiceReference ref)
        {
            m_ref = ref;
            m_map = null;
        }

        public void setSource(Dictionary dict, boolean caseSensitive)
        {
            // Create a map if we don't have one.

            if (m_map == null)
            {
                m_map = new StringMap();
            }
            else
            {
                m_map.clear();
            }

            // Set case comparison accordingly.
            m_map.setCaseSensitive(caseSensitive);

            // Put all dictionary entries into the map.
            if (dict != null)
            {
                Enumeration keys = dict.keys();
                while (keys.hasMoreElements())
                {
                    Object key = keys.nextElement();
                    if (m_map.get(key) == null)
                    {
                        m_map.put(key, dict.get(key));
                    }
                    else
                    {
                        throw new IllegalArgumentException(
                            "Duplicate attribute: " + key.toString());
                    }
                }
            }
            m_ref = null;
        }

        public Object lookup(String name)
        {
            if (m_map == null)
            {
                return m_ref.getProperty(name);
            }
            return m_map.get(name);
        }
    }
}