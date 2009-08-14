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
package org.apache.felix.framework.util.ldap;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

public class Parser
{
    //
    // Parser contants.
    //

    // End of file.
    public static final int EOF = -1;

    // Special characters in parse
    public static final char LPAREN = '(';
    public static final char RPAREN = ')';
    public static final char STAR = '*';

    // Define an enum for substring procedure
    public static final int SIMPLE = 0;
    public static final int PRESENT = 1;
    public static final int SUBSTRING = 2;

    // different from =|>|<|~
    public static final int NOOP = 0;

    // Comparison operators.
    public static final int EQUAL = 0;
    public static final int GREATER_EQUAL = 1;
    public static final int LESS_EQUAL = 2;
    public static final int APPROX = 3;

    // Criteria in % to accept something as approximate.
    public static final int APPROX_CRITERIA = 10;

    // Flag indicating presense of BigDecimal.
    private static boolean m_hasBigDecimal = false;
    
    private static final Class[] STRING_CLASS = new Class[] { String.class };

    static 
    {
        try
        {
            Class.forName("java.math.BigDecimal");
            m_hasBigDecimal = true;
        }
        catch (Exception ex)
        {
            // Ignore.
        }
    }
    //
    // Instance variables.
    //

    private LdapLexer lexer = null;
    private List program;

    public Parser()
    {
        reset();
    }

    public Parser(LdapLexer l)
    {
        reset(l);
    }

    public void reset()
    {
        lexer = null;
        if (program == null)
        {
            program = new ArrayList();
        }
        program.clear();
    }

    public void reset(LdapLexer l)
    {
        reset();
        lexer = l;
    }

    public Object[] getProgram()
    {
        return program.toArray(new Object[program.size()]);
    }

    // Define the recursive descent procedures

    /*
    <start>::= <filter> <EOF>
    */
    public boolean start() throws ParseException, IOException
    {
        boolean ok = filter();
        if (!ok)
        {
            return ok;
        }
        int ch = lexer.get();
        if (ch != EOF)
        {
            throw new ParseException(
                "expected <EOF>; found '" + ((char) ch) + "'");
        }
        return ok;
    }

    /*
    <filter> ::= '(' <filtercomp> ')'
     */
    boolean filter() throws ParseException, IOException
    {
        debug("filter");
        if (lexer.peeknw() != LPAREN)
        {
            return false;
        }
        lexer.get();
        if (!filtercomp())
        {
            throw new ParseException("expected filtercomp");
        }
        if (lexer.getnw() != RPAREN)
        {
            throw new ParseException("expected )");
        }
        return true;
    }

    /*
    <filtercomp> ::= <and> | <or> | <not> | <item>
    <and> ::= '&' <filterlist>
    <or> ::= '|' <filterlist>
    <not> ::= '!' <filter>
    */
    boolean filtercomp() throws ParseException, IOException
    {
        debug("filtercomp");
        int c = lexer.peeknw();
        switch (c)
        {
            case '&' :
            case '|' :
                lexer.get();
                int cnt = filterlist();
                if (cnt == 0)
                {
                    return item((c == '&') ? "&" : "|");
                }
                // Code: [And|Or](cnt)
                program.add(
                    c == '&'
                        ? (Operator) new AndOperator(cnt)
                        : (Operator) new OrOperator(cnt));
                return true;
            case '!' :
                lexer.get();
                if (!filter())
                {
                    return item("!");
                }
                // Code: Not()
                program.add(new NotOperator());
                return true;
            case '=':
            case '>':
            case '<':
            case '~':
            case '(':
            case ')':
            case EOF :
                return false;
            default :
                return item("");        
        }
    }

    /*
    <filterlist> ::= <filter> | <filter> <filterlist>
    */
    int filterlist() throws ParseException, IOException
    {
        debug("filterlist");
        int cnt = 0;
        if (filter())
        {
            do
            {
                cnt++;
            }
            while (filter());
        }
        return (cnt);
    }

    /*
    <item> ::= <simple> | <present> | <substring>
    <simple> ::= <attr> <filtertype> <value>
    <filtertype> ::= <equal> | <approx> | <greater> | <less>
    <present> ::= <attr> '=*'
    <substring> ::= <attr> '=' <initial> <any> <final>
    */
    boolean item(String start) throws ParseException, IOException
    {
        debug("item");

        StringBuffer attr = new StringBuffer(start);
        if (!attribute(attr))
        {
            return false;
        }
        lexer.skipwhitespace(); // assume allowable before equal operator
        // note: I treat the =* case as = followed by a special substring
        int op = equalop();
        if (op == NOOP)
        {
            String oplist = "=|~=|>=|<=";
            throw new ParseException("expected " + oplist);
        }
        ArrayList pieces = new ArrayList();
        int kind = substring(pieces);
        // Get some of the illegal cases out of the way
        if (op != '=' && kind != SIMPLE)
        {
            // We assume that only the = operator can work
            // with right sides containing stars.  If not correct
            // then this code must change.
            throw new ParseException("expected value|substring|*");
        }

        switch (kind)
        {
            case SIMPLE :
                if ((op == '=') && "objectClass".equalsIgnoreCase(attr.toString()))
                {
                    program.add(new ObjectClassOperator((String) pieces.get(0)));
                    return true;
                }
                // Code: Push(attr); Constant(pieces.get(0)); <operator>();
                program.add(new PushOperator(attr.toString()));
                program.add(new ConstOperator(pieces.get(0)));
                switch (op)
                {
                    case '<' :
                        program.add(new LessEqualOperator());
                        break;
                    case '>' :
                        program.add(new GreaterEqualOperator());
                        break;
                    case '~' :
                        program.add(new ApproxOperator());
                        break;
                    case '=' :
                    default :
                        program.add(new EqualOperator());
                }
                break;
            case PRESENT :
                // Code: Present(attr);
                program.add(new PresentOperator(attr.toString()));
                break;
            case SUBSTRING :
                generateSubStringCode(attr.toString(), pieces);
                break;
            default :
                throw new ParseException("expected value|substring|*");
        }
        return true;
    }

    // Generating code for substring right side is mildly
    // complicated.

    void generateSubStringCode(String attr, ArrayList pieces)
    {
        // Code: Push(attr)
        program.add(new PushOperator(attr.toString()));

        // Convert the pieces arraylist to a String[]
        String[] list =
            (String[]) pieces.toArray(new String[pieces.size()]);

        // Code: SubString(list)
        program.add(new SubStringOperator(list));
    }

    /*
    <attr> is a string representing an attributte,
    or key, in the properties
    objects of the registered services. Attribute names are not case
    sensitive; that is cn and CN both refer to the same attribute.
    Attribute names may have embedded spaces, but not leading or
    trailing spaces.
    */
    boolean attribute(StringBuffer buf) throws ParseException, IOException
    {
        debug("attribute");
        lexer.skipwhitespace();
        int c = lexer.peek(); 
        // need to make sure there
        // is at least one KEYCHAR
        switch (c) 
        {
            case '=':
            case '>':
            case '<':
            case '~':
            case '(':
            case ')':
            case EOF:
                return false;
            default:
                break;
        }

        boolean parsing = true;
        while (parsing)
        {
            buf.append((char) lexer.get());
            c = lexer.peek();
            switch (c) 
            {
                case '=':
                case '>':
                case '<':
                case '~':
                case '(':
                case ')':
                case EOF:
                    parsing = false;
                default:
                    break;
            }
        }

        // The above may have accumulated trailing blanks that must be removed
        int i = buf.length() - 1;
        while (i > 0 && Character.isWhitespace(buf.charAt(i)))
        {
            i--;
        }
        buf.setLength(i + 1);
        return true;
    }

    /*
       <equal> ::= '='
       <approx> ::= '~='
       <greater> ::= '>='
       <less> ::= '<='
       <present> ::= <attr> '=*'
    */
    int equalop() throws ParseException, IOException
    {
        debug("equalop");
        lexer.skipwhitespace();
        int op = lexer.peek();
        switch (op)
        {
            case '=' :
                lexer.get();
                break;
            case '~' :
            case '<' :
            case '>' :
                // skip main operator char
                int c = lexer.get();
                // make sure that the next char is '='
                c = lexer.get();
                if (c != '=')
                {
                    throw new ParseException("expected ~=|>=|<=");
                }
                break;
            default :
                op = NOOP;
        }
        return op;
    }

    /*
    <substring> ::= <attr> '=' <initial> <any> <final>
    <initial> ::= NULL | <value>
    <any> ::= '*' <starval>
    <starval> ::= NULL | <value> '*' <starval>
    <final> ::= NULL | <value>
    <value> ::= ...
    */
    /*
    This procedure handles all cases on right side of an item
    */
    int substring(ArrayList pieces) throws ParseException, IOException
    {
        debug("substring");

        pieces.clear();
        StringBuffer ss = new StringBuffer();
        //        int kind = SIMPLE; // assume until proven otherwise
        boolean wasStar = false; // indicates last piece was a star
        boolean leftstar = false; // track if the initial piece is a star
        boolean rightstar = false; // track if the final piece is a star

        // We assume (sub)strings can contain leading and trailing blanks
loop:   for (;;)
        {
            int c = lexer.peek();
            switch (c)
            {
                case RPAREN :
                    if (wasStar)
                    {
                        // insert last piece as "" to handle trailing star
                        rightstar = true;
                    }
                    else
                    {
                        pieces.add(ss.toString());
                        // accumulate the last piece
                        // note that in the case of
                        // (cn=); this might be
                        // the string "" (!=null)
                    }
                    ss.setLength(0);
                    break loop;
                case '\\' :
                    wasStar = false;
                    lexer.get();
                    c = lexer.get();
                    if (c == EOF)
                    {
                        throw new ParseException("unexpected EOF");
                    }
                    ss.append((char) c);
                    break;
                case LPAREN:
                case EOF :
                    if (pieces.size() > 0)
                    {
                        throw new ParseException("expected ')'");
                    }
                    else
                    {
                        throw new ParseException("expected value|substring");
                    }
                case '*' :
                    if (wasStar)
                    {
                        // encountered two successive stars;
                        // I assume this is illegal
                        throw new ParseException("unexpected '**'");
                    }
                    lexer.get();
                    if (ss.length() > 0)
                    {
                        pieces.add(ss.toString()); // accumulate the pieces
                        // between '*' occurrences
                    }
                    ss.setLength(0);
                    // if this is a leading star, then track it
                    if (pieces.size() == 0)
                    {
                        leftstar = true;
                    }
                    ss.setLength(0);
                    wasStar = true;
                    break;
                default :
                    wasStar = false;
                    ss.append((char) lexer.get());
            }
        }
        if (pieces.size() == 0)
        {
            return PRESENT;
        }
        if (leftstar || rightstar || pieces.size() > 1)
        {
            // insert leading and/or trailing "" to anchor ends
            if (rightstar)
            {
                pieces.add("");
            }
            if (leftstar)
            {
                pieces.add(0, "");
            }
            return SUBSTRING;
        }
        // assert !leftstar && !rightstar && pieces.size == 1
        return SIMPLE;
    }

    // Debug stuff

    static boolean debug = false;

    PrintStream dbgout = null;

    public void setDebug(PrintStream out)
    {
        debug = true;
        dbgout = out;
    }

    void debug(String proc)
    {
        if (!debug || dbgout == null)
        {
            return;
        }
        dbgout.println("parsing " + proc + ":" + lexer.charno());
        dbgout.flush();
    }

    // Exclusive inner classes
    private static final class AndOperator extends Operator
    {
        private final int operandCount;

        public AndOperator(int opcnt)
        {
            operandCount = opcnt;
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            // Determine result using short-circuit evaluation.
            boolean result = true;
            for (int i = 0; i < operandCount; i++)
            {
                if (operands.empty())
                {
                    fewOperands("AND");
                }

                // For short-circuited evaluation, once the AND
                // becomes false, we can ignore the remaining
                // expressions, but we must still pop them off.
                if (!result)
                {
                    operands.pop();
                }
                else
                {
                    result = ((Boolean) operands.pop()).booleanValue();
                }
            }
            operands.push((result ? Boolean.TRUE : Boolean.FALSE));
        }

        public String toString()
        {
            return "&(" + operandCount + ")";
        }

        public void buildTree(Stack operands)
        {
            if (children == null)
            {
                Operator[] tmp = new Operator[operandCount];
                // need to preserve stack order
                for (int i = 0; i < operandCount; i++)
                {
                    tmp[(operandCount - 1) - i] =
                        (Operator) operands.pop();
                }
                children = tmp;
            }
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append("(&");
            for (int i = 0; i < children.length; i++)
            {
                Operator o = (Operator) children[i];
                o.toStringInfix(b);
            }
            b.append(")");
        }
    }

    private static final class OrOperator extends Operator
    {
        private final int operandCount;

        public OrOperator(int opcnt)
        {
            operandCount = opcnt;
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            // Determine result using short-circuit evaluation.
            boolean result = false;
            for (int i = 0; i < operandCount; i++)
            {
                if (operands.empty())
                {
                    fewOperands("OR");
                }

                // For short-circuited evaluation, once the OR
                // becomes true, we can ignore the remaining
                // expressions, but we must still pop them off.
                if (result)
                {
                    operands.pop();
                }
                else
                {
                    result = ((Boolean) operands.pop()).booleanValue();
                }
            }
            operands.push((result ? Boolean.TRUE : Boolean.FALSE));
        }

        public String toString()
        {
            return "|(" + operandCount + ")";
        }

        public void buildTree(Stack operands)
        {
            if (children == null) 
            {
                Operator[] tmp = new Operator[operandCount];
            
                // need to preserve stack order
                for (int i = 0; i < operandCount; i++)
                {
                    tmp[(operandCount - 1) - i] =
                        (Operator) operands.pop();
                }
                children = tmp;
            }
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append("(|");
            for (int i = 0; i < children.length; i++)
            {
                Operator o = (Operator) children[i];
                o.toStringInfix(b);
            }
            b.append(")");
        }
    }

    private static final class NotOperator extends Operator
    {
        public NotOperator()
        {
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            if (operands.empty())
            {
                fewOperands("NOT");
            }
            boolean result = !((Boolean) operands.pop()).booleanValue();
            operands.push((result ? Boolean.TRUE : Boolean.FALSE));
        }

        public String toString()
        {
            return "!()";
        }

        public void buildTree(Stack operands)
        {
            if (children == null)
            {
                children = new Operator[]{
                    (Operator) operands.pop()};
            }
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append("(!");
            for (int i = 0; i < children.length; i++)
            {
                Operator o = (Operator) children[i];
                o.toStringInfix(b);
            }
            b.append(")");
        }
    }

    private static final class ObjectClassOperator extends Operator
    {
        public final String m_target;
        
        public ObjectClassOperator(String target)
        {
            m_target = target;
        }

        public void buildTree(Stack operands)
        {
            operands.push(this);
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            String[] objectClass = (String[]) mapper.lookup("objectClass");
            if (objectClass != null)
            {
                for (int i = 0; i < objectClass.length; i++)
                {
                    if (m_target.equals(objectClass[i]))
                    {
                        operands.push(Boolean.TRUE);
                        return;
                    }
                }
            }
            operands.push(Boolean.FALSE);
        }

        public String toString()
        {
            return "=()";
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append('(').append("objectClass=").append(m_target).append(')');
        }
    }

    private static final class EqualOperator extends Operator
    {
        public EqualOperator()
        {
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            if (operands.empty())
            {
                fewOperands("=");
            }

            // We cheat and use the knowledge that top (right) operand
            // will always be a string because of the way code was generated
            String rhs = (String) operands.pop();
            if (operands.empty())
            {
                fewOperands("=");
            }

            Object lhs = operands.pop();

            operands.push((compare(lhs, rhs, EQUAL) ? Boolean.TRUE : Boolean.FALSE));
        }

        public String toString()
        {
            return "=()";
        }

        public void buildTree(Stack operands)
        {
            if (children == null)
            { 
                Operator[] tmp = new Operator[2];
            
                // need to preserve stack order
                for (int i = 0; i < 2; i++)
                {
                    Operator o = (Operator) operands.pop();
                    tmp[1 - i] = o;
                }
                children = tmp;
            }
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append("(");
            for (int i = 0; i < children.length; i++)
            {
                Operator o = (Operator) children[i];
                if (i > 0)
                {
                    b.append("=");
                }
                o.toStringInfix(b);
            }
            b.append(")");
        }
    }

    private static final class GreaterEqualOperator extends Operator
    {
        public GreaterEqualOperator()
        {
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            if (operands.empty())
            {
                fewOperands(">=");
            }
            // We cheat and use the knowledge that top (right) operand
            // will always be a string because of the way code was generated
            String rhs = (String) operands.pop();
            if (operands.empty())
            {
                fewOperands(">=");
            }
            Object lhs = operands.pop();

            operands.push((compare(lhs, rhs, GREATER_EQUAL) ? Boolean.TRUE : Boolean.FALSE));
        }

        public String toString()
        {
            return ">=()";
        }

        public void buildTree(Stack operands)
        {
            if (children == null)
            {
                Operator[] tmp = new Operator[2];
            
                // need to preserve stack order
                for (int i = 0; i < 2; i++)
                {
                    tmp[1 - i] = (Operator) operands.pop();
                }
                children = tmp;
            }
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append("(");
            for (int i = 0; i < children.length; i++)
            {
                Operator o = (Operator) children[i];
                if (i > 0)
                {
                    b.append(">=");
                }
                o.toStringInfix(b);
            }
            b.append(")");
        }
    }

    private static final class LessEqualOperator extends Operator
    {
        public LessEqualOperator()
        {
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            if (operands.empty())
            {
                fewOperands("<=");
            }
            // We cheat and use the knowledge that top (right) operand
            // will always be a string because of the way code was generated
            String rhs = (String) operands.pop();
            if (operands.empty())
            {
                fewOperands("<=");
            }
            Object lhs = (Object) operands.pop();
            operands.push((compare(lhs, rhs, LESS_EQUAL) ? Boolean.TRUE : Boolean.FALSE));
        }

        public String toString()
        {
            return "<=()";
        }

        public void buildTree(Stack operands)
        {
            if (children == null)
            {
                Operator[] tmp = new Operator[2];
            
                // need to preserve stack order
                for (int i = 0; i < 2; i++)
                {
                    tmp[1 - i] = (Operator) operands.pop();
                }
                children = tmp;
            }
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append("(");
            for (int i = 0; i < children.length; i++)
            {
                Operator o = (Operator) children[i];
                if (i > 0)
                {
                    b.append("<=");
                }
                o.toStringInfix(b);
            }
            b.append(")");
        }
    }

    private static final class ApproxOperator extends Operator
    {
        public ApproxOperator()
        {
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            if (operands.empty())
            {
                fewOperands("~=");
            }
            // We cheat and use the knowledge that top (right) operand
            // will always be a string because of the way code was generated
            String rhs = (String) operands.pop();
            if (operands.empty())
            {
                fewOperands("~=");
            }
            Object lhs = operands.pop();
            operands.push((compare(lhs, rhs, APPROX) ? Boolean.TRUE : Boolean.FALSE));
        }

        public String toString()
        {
            return "~=()";
        }

        public void buildTree(Stack operands)
        {
            if (children == null)
            {
                Operator[] tmp = new Operator[2];
            
                // need to preserve stack order
                for (int i = 0; i < 2; i++)
                {
                    tmp[1 - i] = (Operator) operands.pop();
                }
                children = tmp;
            }
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append("(");
            for (int i = 0; i < children.length; i++)
            {
                Operator o = (Operator) children[i];
                if (i > 0)
                {
                    b.append("~=");
                }
                o.toStringInfix(b);
            }
            b.append(")");
        }
    }

    private static final class PresentOperator extends Operator
    {
        final String attribute;

        public PresentOperator(String attribute)
        {
            this.attribute = attribute;
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            Object value = mapper.lookup(attribute);
            operands.push((value != null) ? Boolean.TRUE : Boolean.FALSE);
        }

        public String toString()
        {
            return attribute + "=*";
        }

        public void buildTree(Stack operands)
        {
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append("(");
            b.append(attribute + "=*");
            b.append(")");
        }
    }

    private static final class PushOperator extends Operator
    {
        final String attribute;

        public PushOperator(String attribute)
        {
            this.attribute = attribute;
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            // find and push the value of a given attribute
            Object value = mapper.lookup(attribute);
            if (value == null)
            {
                throw new AttributeNotFoundException(
                    "attribute " + attribute + " not found");
            }
            operands.push(value);
        }

        public String toString()
        {
            return "push(" + attribute + ")";
        }

        public String toStringInfix()
        {
            return attribute;
        }

        public void buildTree(Stack operands)
        {
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append(attribute);
        }
    }

    private static final class ConstOperator extends Operator
    {
        final Object val;

        public ConstOperator(Object val)
        {
            this.val = val;
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            operands.push(val);
        }

        public String toString()
        {
            return "const(" + val + ")";
        }

        public String toStringInfix()
        {
            return val.toString();
        }

        public void buildTree(Stack operands)
        {
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            appendEscaped(b, val.toString());
        }
    }

    private static final class SubStringOperator extends Operator
        implements OperatorConstants
    {
        final String[] pieces;

        public SubStringOperator(String[] pieces)
        {
            this.pieces = pieces;
        }

        public void execute(Stack operands, Mapper mapper)
            throws EvaluationException
        {
            if (operands.empty())
            {
                fewOperands("SUBSTRING");
            }

            Object op = operands.pop();

            // The operand can either be a string or an array of strings.
            if (op instanceof String)
            {
                operands.push(check((String) op));
            }
            else if (op instanceof String[])
            {
                // If one element of the array matches, then push true.
                String[] ops = (String[]) op;
                boolean result = false;
                for (int i = 0; !result && (i < ops.length); i++)
                {
                    if (check(ops[i]) == Boolean.TRUE)
                    {
                        result = true;
                    }
                }

                operands.push(result ? Boolean.TRUE : Boolean.FALSE);
            }
            else
            {
                unsupportedType("SUBSTRING", op.getClass());
            }
        }

        private Boolean check(String s)
        {
            // Walk the pieces to match the string
            // There are implicit stars between each piece,
            // and the first and last pieces might be "" to anchor the match.
            // assert (pieces.length > 1)
            // minimal case is <string>*<string>

            Boolean result = Boolean.FALSE;
            int len = pieces.length;

            int index = 0;
            for (int i = 0; i < len; i++)
            {
                String piece = (String) pieces[i];
                
                if (i == len - 1)
                {
                    // this is the last piece
                    if (s.endsWith(piece))
                    {
                        result = Boolean.TRUE;
                    }
                    else
                    {
                        result = Boolean.FALSE;
                    }
                    break;
                }
                // initial non-star; assert index == 0
                else if (i == 0)
                {
                    if (!s.startsWith(piece))
                    {
                        result = Boolean.FALSE;
                        break;
                    }
                }
                // assert i > 0 && i < len-1
                else
                {
                    // Sure wish stringbuffer supported e.g. indexOf
                    index = s.indexOf(piece, index);
                    if (index < 0)
                    {
                        result = Boolean.FALSE;
                        break;
                    }
                }
                // start beyond the matching piece
                index += piece.length();
            }

            return result;
        }

        public String toString()
        {
            StringBuffer b = new StringBuffer();
            b.append("substring(");
            for (int i = 0; i < pieces.length; i++)
            {
                String piece = pieces[i];
                if (i > 0)
                {
                    b.append("*");
                }
                b.append(escape(piece));
            }
            b.append(")");
            return b.toString();
        }

        public String escape(String s)
        {
            int len = s.length();
            StringBuffer buf = new StringBuffer(len);
            for (int i = 0; i < len; i++)
            {
                char c = s.charAt(i);
                if (c == ')' || c == '*')
                    buf.append('\\');
                buf.append(c);
            }
            return buf.toString();
        }

        public void buildTree(Stack operands)
        {
            if (children == null) 
            {
                children = new Operator[]{
                    (Operator) operands.pop()};
            }
            operands.push(this);
        }

        public void toStringInfix(StringBuffer b)
        {
            b.append("(");
            children[0].toStringInfix(b); // dump attribute
            b.append("=");
            for (int i = 0; i < pieces.length; i++)
            {
                String piece = pieces[i];
                if (i > 0)
                {
                    b.append("*");
                }
                appendEscaped(b, piece);
            }
            b.append(")");
        }
    }

    // Utility classes and Interfaces

    private interface OperatorConstants
    {
        static final int SSINIT = 0;
        static final int SSFINAL = 1;
        static final int SSMIDDLE = 2;
        static final int SSANY = 3;
    }

    /**
     * Compare two operands in an expression with respect  
     * to the following operators =, <=, >= and ~=
     * 
     * Example: value=100
     *
     * @param lhs an object that implements comparable or an array of
     *            objects that implement comparable.
     * @param rhs a string representing the right operand.
     * @param operator an integer that represents the operator.
     * @return <tt>true</tt> or <tt>false</tt> according to the evaluation.
     * @throws EvaluationException if it is not possible to do the comparison.
    **/
    public static boolean compare(Object lhs, String rhs, int operator)
        throws EvaluationException
    {
        // Try to optimize the common case of strings by just
        // checking for them directly.
        if (lhs instanceof String)
        {
            switch (operator)
            {
                case EQUAL :
                    return (((String) lhs).compareTo(rhs) == 0);
                case GREATER_EQUAL :
                    return (((String) lhs).compareTo(rhs) >= 0);
                case LESS_EQUAL :
                    return (((String) lhs).compareTo(rhs) <= 0);
                case APPROX:
                    return compareToApprox(((String) lhs), rhs);
                default:
                    throw new EvaluationException("Unknown comparison operator..."
                        + operator);
            }
        }
        else if (lhs instanceof Comparable)
        {
            // Here we know that the LHS is a comparable object, so
            // try to create an object for the RHS by using a constructor
            // that will take the RHS string as a parameter.
            Comparable rhsComparable = null;
            try
            {
                // We are expecting to be able to construct a comparable
                // instance from the RHS string by passing it into the
                // constructor of the corresponing comparable class. The
                // Character class is a special case, since its constructor
                // does not take a string, so handle it separately.
                if (lhs instanceof Character)
                {
                    rhsComparable = new Character(rhs.charAt(0));
                }
                else
                {
                    rhsComparable = (Comparable) lhs.getClass()
                        .getConstructor(STRING_CLASS)
                            .newInstance(new Object[] { rhs });
                }
            }
            catch (Exception ex)
            {
                throw new EvaluationException(
                    "Could not instantiate class "
                        + lhs.getClass().getName()
                        + " with constructor String parameter "
                        + rhs + " " + ex);
            }

            Comparable lhsComparable = (Comparable) lhs;

            switch (operator)
            {
                case EQUAL :
                    return (lhsComparable.compareTo(rhsComparable) == 0);
                case GREATER_EQUAL :
                    return (lhsComparable.compareTo(rhsComparable) >= 0);
                case LESS_EQUAL :
                    return (lhsComparable.compareTo(rhsComparable) <= 0);
                case APPROX:
                    return compareToApprox(lhsComparable, rhsComparable);
                default:
                    throw new EvaluationException("Unknown comparison operator..."
                        + operator);
            }
        }

        // Determine class of LHS.
        Class lhsClass = lhs.getClass();

        // If LHS is an array, then call compare() on each element
        // of the array until a match is found.
        if (lhsClass.isArray())
        {
            // First, if this is an array of primitives, then convert
            // the entire array to an array of the associated
            // primitive wrapper class instances.
            if (lhsClass.getComponentType().isPrimitive())
            {
                lhs = convertPrimitiveArray(lhs);
            }

            // Now call compare on each element of array.
            Object[] array = (Object[]) lhs;
            for (int i = 0; i < array.length; i++)
            {
                if (compare(array[i], rhs, operator))
                {
                    return true;
                }
            }
        }
        // If LHS is a vector, then call compare() on each element
        // of the vector until a match is found.
        else if (lhs instanceof Collection)
        {
            for (Iterator iter = ((Collection) lhs).iterator(); iter.hasNext();)
            {
                if (compare(iter.next(), rhs, operator))
                {
                    return true;
                }
            }
        }
        else
        {
            // At this point we are expecting the LHS to be a comparable,
            // but Boolean is a special case since it is the only primitive
            // wrapper class that does not implement comparable; deal with
            // Boolean separately.
            if (lhsClass == Boolean.class)
            {
                return compareBoolean(lhs, rhs, operator);
            }

            // If the LHS is not a comparable, then try to use simple
            // equals() comparison. If that fails, return false.
            if (!(Comparable.class.isAssignableFrom(lhsClass)))
            {
                try
                {
                    Object rhsObject = lhsClass
                        .getConstructor(STRING_CLASS)
                            .newInstance(new Object[] { rhs });
                        return lhs.equals(rhsObject);
                }
                catch (Exception ex)
                {
                    // Always return false.
                }

                return false;
            }

        }

        return false;
    }

    private static void appendEscaped(StringBuffer buf, String value) 
    {
        for (int i = 0; i < value.length(); i++) 
        {
            char c = value.charAt(i);
            if (c == '(' || c == ')' || c == '*' || c == '\\') 
            {
                buf.append('\\');
            }
            buf.append(c);
        }
    }
    
    /**
     * This is an ugly utility method to convert an array of primitives
     * to an array of primitive wrapper objects. This method simplifies
     * processing LDAP filters since the special case of primitive arrays
     * can be ignored.
     * @param array
     * @return
    **/
    private static Object[] convertPrimitiveArray(Object array)
    {
        Class clazz = array.getClass().getComponentType();

        if (clazz == Boolean.TYPE)
        {
            boolean[] src = (boolean[]) array;
            array = new Boolean[src.length];
            for (int i = 0; i < src.length; i++)
            {
                ((Object[]) array)[i] = (src[i] ? Boolean.TRUE : Boolean.FALSE);
            }
        }
        else if (clazz == Character.TYPE)
        {
            char[] src = (char[]) array;
            array = new Character[src.length];
            for (int i = 0; i < src.length; i++)
            {
                ((Object[]) array)[i] = new Character(src[i]);
            }
        }
        else if (clazz == Byte.TYPE)
        {
            byte[] src = (byte[]) array;
            array = new Byte[src.length];
            for (int i = 0; i < src.length; i++)
            {
                ((Object[]) array)[i] = new Byte(src[i]);
            }
        }
        else if (clazz == Short.TYPE)
        {
            byte[] src = (byte[]) array;
            array = new Byte[src.length];
            for (int i = 0; i < src.length; i++)
            {
                ((Object[]) array)[i] = new Byte(src[i]);
            }
        }
        else if (clazz == Integer.TYPE)
        {
            int[] src = (int[]) array;
            array = new Integer[src.length];
            for (int i = 0; i < src.length; i++)
            {
                ((Object[]) array)[i] = new Integer(src[i]);
            }
        }
        else if (clazz == Long.TYPE)
        {
            long[] src = (long[]) array;
            array = new Long[src.length];
            for (int i = 0; i < src.length; i++)
            {
                ((Object[]) array)[i] = new Long(src[i]);
            }
        }
        else if (clazz == Float.TYPE)
        {
            float[] src = (float[]) array;
            array = new Float[src.length];
            for (int i = 0; i < src.length; i++)
            {
                ((Object[]) array)[i] = new Float(src[i]);
            }
        }
        else if (clazz == Double.TYPE)
        {
            double[] src = (double[]) array;
            array = new Double[src.length];
            for (int i = 0; i < src.length; i++)
            {
                ((Object[]) array)[i] = new Double(src[i]);
            }
        }

        return (Object[]) array;
    }

    private static boolean compareBoolean(Object lhs, String rhs, int operator)
        throws EvaluationException
    {
        Boolean rhsBoolean = new Boolean(rhs);
        if (lhs.getClass().isArray())
        {
            Object[] objs = (Object[]) lhs;
            for (int i = 0; i < objs.length; i++)
            {
                switch (operator)
                {
                    case EQUAL :
                    case GREATER_EQUAL :
                    case LESS_EQUAL :
                    case APPROX:
                        if (objs[i].equals(rhsBoolean))
                        {
                            return true;
                        }
                        break;
                    default:
                        throw new EvaluationException(
                            "Unknown comparison operator: " + operator);   
                }
            }
            return false;
        }
        else
        {
            switch (operator)
            {
                case EQUAL :
                case GREATER_EQUAL :
                case LESS_EQUAL :
                case APPROX:
                    return (lhs.equals(rhsBoolean));
                default:
                    throw new EvaluationException("Unknown comparison operator..."
                        + operator);
            }
        }
    }

    /**
     * Test if two objects are approximate. The two objects that are passed must
     * have the same type.
     * 
     * Approximate for numerical values involves a difference of less than APPROX_CRITERIA
     * Approximate for string values is calculated by using the Levenshtein distance
     * between strings and is case insensitive. Less than APPROX_CRITERIA of
     * difference is considered as approximate.
     * 
     * Supported types only include the following subclasses of Number:
     * - Byte
     * - Double
     * - Float
     * - Int
     * - Long
     * - Short 
     * - BigInteger
     * - BigDecimal
     * As subclasses of Number must provide methods to convert the represented numeric value 
     * to byte, double, float, int, long, and short. (see API)
     * 
     * @param obj1
     * @param obj2
     * @return true if they are approximate
     * @throws EvaluationException if it the two objects cannot be approximated
    **/
    private static boolean compareToApprox(Object obj1, Object obj2) throws EvaluationException
    {
        if (obj1 instanceof Byte)
        {
            byte value1 = ((Byte)obj1).byteValue();
            byte value2 = ((Byte)obj2).byteValue();
            return (value2 >= (value1-((Math.abs(value1)*(byte)APPROX_CRITERIA)/(byte)100)) 
                && value2 <= (value1+((Math.abs(value1)*(byte)APPROX_CRITERIA)/(byte)100)));
        }
        else if (obj1 instanceof Character)
        {
            char value1 = ((Character)obj1).charValue();
            char value2 = ((Character)obj2).charValue();
            return (value2 >= (value1-((Math.abs(value1)*(char)APPROX_CRITERIA)/(char)100)) 
                && value2 <= (value1+((Math.abs(value1)*(char)APPROX_CRITERIA)/(char)100)));
        }
        else if (obj1 instanceof Double)
        {
            double value1 = ((Double)obj1).doubleValue();
            double value2 = ((Double)obj2).doubleValue();
            return (value2 >= (value1-((Math.abs(value1)*(double)APPROX_CRITERIA)/(double)100)) 
                && value2 <= (value1+((Math.abs(value1)*(double)APPROX_CRITERIA)/(double)100)));
        }
        else if (obj1 instanceof Float)
        {
            float value1 = ((Float)obj1).floatValue();
            float value2 = ((Float)obj2).floatValue();
            return (value2 >= (value1-((Math.abs(value1)*(float)APPROX_CRITERIA)/(float)100)) 
                && value2 <= (value1+((Math.abs(value1)*(float)APPROX_CRITERIA)/(float)100)));
        }
        else if (obj1 instanceof Integer)
        {
            int value1 = ((Integer)obj1).intValue();
            int value2 = ((Integer)obj2).intValue();
            return (value2 >= (value1-((Math.abs(value1)*(int)APPROX_CRITERIA)/(int)100)) 
                && value2 <= (value1+((Math.abs(value1)*(int)APPROX_CRITERIA)/(int)100)));
        }
        else if (obj1 instanceof Long)
        {
            long value1 = ((Long)obj1).longValue();
            long value2 = ((Long)obj2).longValue();
            return (value2 >= (value1-((Math.abs(value1)*(long)APPROX_CRITERIA)/(long)100)) 
                && value2 <= (value1+((Math.abs(value1)*(long)APPROX_CRITERIA)/(long)100)));
        }
        else if (obj1 instanceof Short)
        {
            short value1 = ((Short)obj1).shortValue();
            short value2 = ((Short)obj2).shortValue();
            return (value2 >= (value1-((Math.abs(value1)*(short)APPROX_CRITERIA)/(short)100)) 
                && value2 <= (value1+((Math.abs(value1)*(short)APPROX_CRITERIA)/(short)100)));
        }
        else if (obj1 instanceof String)
        {
            int distance = getDistance(
                obj1.toString().toLowerCase(), obj2.toString().toLowerCase());
            int size = ((String)obj1).length();
            return (distance <= ((size*APPROX_CRITERIA)/100));
        }
        else if (obj1 instanceof BigInteger)
        {
            BigInteger value1 = (BigInteger)obj1;
            BigInteger value2 = (BigInteger)obj2;
            BigInteger delta = value1.abs().multiply(
                BigInteger.valueOf(APPROX_CRITERIA)
                    .divide(BigInteger.valueOf(100)));
            BigInteger low = value1.subtract(delta);
            BigInteger high = value1.add(delta);
            return (value2.compareTo(low) >= 0) && (value2.compareTo(high) <= 0);
        }
        else if (m_hasBigDecimal && (obj1 instanceof BigDecimal))
        {
            BigDecimal value1 = (BigDecimal)obj1;
            BigDecimal value2 = (BigDecimal)obj2;
            BigDecimal delta = value1.abs().multiply(
                BigDecimal.valueOf(APPROX_CRITERIA)
                    .divide(BigDecimal.valueOf(100), BigDecimal.ROUND_HALF_DOWN));
            BigDecimal low = value1.subtract(delta);
            BigDecimal high = value1.add(delta);
            return (value2.compareTo(low) >= 0) && (value2.compareTo(high) <= 0);
        }
        throw new EvaluationException(
            "Approximate operator not supported for type "
            + obj1.getClass().getName());
    }

    /**
     * Calculate the Levenshtein distance (LD) between two strings.
     * The Levenshteing distance is a measure of the similarity between 
     * two strings, which we will refer to as the source string (s) and 
     * the target string (t). The distance is the number of deletions, 
     * insertions, or substitutions required to transform s into t.
     * 
     * Algorithm from: http://www.merriampark.com/ld.htm
     * 
     * @param s the first string
     * @param t the second string
     * @return
     */
    private static int getDistance(String s, String t)
    {
        int d[][]; // matrix
        int n; // length of s
        int m; // length of t
        int i; // iterates through s
        int j; // iterates through t
        char s_i; // ith character of s
        char t_j; // jth character of t
        int cost; // cost

        // Step 1
        n = s.length();
        m = t.length();
        if (n == 0)
        {
            return m;
        }
        if (m == 0)
        {
            return n;
        }
        d = new int[n + 1][m + 1];

        // Step 2
        for (i = 0; i <= n; i++)
        {
            d[i][0] = i;
        }

        for (j = 0; j <= m; j++)
        {
            d[0][j] = j;
        }

        // Step 3
        for (i = 1; i <= n; i++)
        {
            s_i = s.charAt(i - 1);
            // Step 4
            for (j = 1; j <= m; j++)
            {
                t_j = t.charAt(j - 1);
                // Step 5
                if (s_i == t_j)
                {
                    cost = 0;
                }
                else
                {
                    cost = 1;
                }
                // Step 6
                d[i][j] =
                    Minimum(
                        d[i - 1][j] + 1,
                        d[i][j - 1] + 1,
                        d[i - 1][j - 1] + cost);
            }
        }
        // Step 7
        return d[n][m];
    }

    /**
     * Calculate the minimum between three values
     * 
     * @param a
     * @param b
     * @param c
     * @return
     */
    private static int Minimum(int a, int b, int c)
    {
        int mi;
        mi = a;
        if (b < mi)
        {
            mi = b;
        }
        if (c < mi)
        {
            mi = c;
        }
        return mi;
    }

    private static void fewOperands(String op) throws EvaluationException
    {
        throw new EvaluationException(op + ": too few operands");
    }

    private static void unsupportedType(String opStr, Class clazz)
        throws EvaluationException
    {
        throw new EvaluationException(
            opStr + ": unsupported type " + clazz.getName(), clazz);
    }
}
