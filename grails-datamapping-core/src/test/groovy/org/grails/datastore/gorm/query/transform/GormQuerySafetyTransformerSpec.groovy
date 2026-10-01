/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.grails.datastore.gorm.query.transform

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.messages.WarningMessage

import spock.lang.Specification
import spock.lang.Unroll

class GormQuerySafetyTransformerSpec extends Specification {

    private List<WarningMessage> compileAndCollectWarnings(String source) {
        CompilationUnit unit = new CompilationUnit(new GroovyClassLoader())
        def sourceUnit = unit.addSource("Book.groovy", source)
        unit.compile(Phases.CANONICALIZATION)
        sourceUnit.getErrorCollector().getWarnings() ?: []
    }

    void "test String query flattened from an interpolated GString before executeQuery fails to compile"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        String q = "from Book where title = ${title}"
        executeQuery(q)
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
        e.message.contains("passed to 'executeQuery'")
    }

    @Unroll
    void "test flattening via #description before find fails to compile"() {
        when:
        new GroovyClassLoader().parseClass("""
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static Book byTitle(String title) {
        $declaration
        find(q)
    }
}
""")

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')

        where:
        description        | declaration
        '.toString()'      | 'def q = "from Book where title = ${title}".toString()'
        'as String'        | 'String q = ("from Book where title = ${title}" as String)'
        '(String) cast'    | 'String q = (String) "from Book where title = ${title}"'
        'reassignment'     | 'String q; q = "from Book where title = ${title}"'
    }

    @Unroll
    void "test inline #description at the call site fails to compile"() {
        when:
        new GroovyClassLoader().parseClass("""
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        executeQuery($argument)
    }
}
""")

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')

        where:
        description     | argument
        '.toString()'   | '"from Book where title = ${title}".toString()'
        '(String) cast' | '(String) "from Book where title = ${title}"'
        'as String'     | '("from Book where title = ${title}" as String)'
    }

    @Unroll
    void "test aliasing via #description before find fails to compile"() {
        when:
        new GroovyClassLoader().parseClass("""
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static Book byTitle(String title) {
        $declaration
        find(q)
    }
}
""")

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')

        where:
        description                      | declaration
        'a variable holding a GString'   | 'def g = "from Book where title = ${title}"\nString q = g'
        '.toString() on an alias'        | 'def g = "from Book where title = ${title}"\nString q = g.toString()'
        'a two-hop alias chain'          | 'def g = "from Book where title = ${title}"\ndef h = g\nString q = h'
        'assigning an already-flattened alias' | 'String p = "from Book where title = ${title}"\nString q = p'
    }

    void "test a variable flattened in only one branch of an if/else still fails to compile"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static Book byTitle(String title, boolean condition) {
        String q
        if (condition) {
            q = "from Book where title = ${title}"
        } else {
            q = "from Book"
        }
        find(q)
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
    }

    void "test a variable flattened only in the else branch still fails to compile"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static Book byTitle(String title, boolean condition) {
        String q
        if (condition) {
            q = "from Book"
        } else {
            q = "from Book where title = ${title}"
        }
        find(q)
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
    }

    void "test if/else where both branches build a safe query compiles cleanly"() {
        when:
        Class<?> bookClass = new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static Book byTitle(String title, boolean condition) {
        String q
        if (condition) {
            q = "from Book where title = :title"
        } else {
            q = "from Book"
        }
        find(q, [title: title])
    }
}
''')

        then:
        bookClass != null
    }

    void "test aliasing a plain non-interpolated variable compiles cleanly"() {
        when:
        Class<?> bookClass = new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List all() {
        def g = "from Book"
        String q = g
        executeQuery(q)
    }
}
''')

        then:
        bookClass != null
    }

    void "test a GString literal passed directly compiles cleanly"() {
        when:
        Class<?> bookClass = new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        executeQuery("from Book where title = ${title}")
    }
}
''')

        then:
        bookClass != null
    }

    void "test a plain non-interpolated String compiles cleanly"() {
        when:
        Class<?> bookClass = new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List all() {
        String q = "from Book"
        executeQuery(q)
    }
}
''')

        then:
        bookClass != null
    }

    void "test a flattened query suppressed with @SuppressWarnings compiles cleanly"() {
        when:
        Class<?> bookClass = new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    @SuppressWarnings("GormUnsafeQueryString")
    static List byTitle(String title) {
        String q = "from Book where title = ${title}"
        executeQuery(q)
    }
}
''')

        then:
        bookClass != null
    }

    void "test a non-domain class with its own find/findAll(String) methods does not false-positive"() {
        when:
        Class<?> planClass = new GroovyClassLoader().parseClass('''
class Plan {
    String find(String query) {
        return query
    }

    List findAll(String query) {
        return [query]
    }

    static String byName(String name) {
        String q = "plan ${name}"
        new Plan().find(q)
    }
}
''')

        then:
        planClass != null
    }

    void "test Collection.find/.findAll with a closure argument does not false-positive"() {
        when:
        Class<?> bookClass = new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static Object pickOne(List titles, String target) {
        String q = "picking ${target}"
        titles.find { it == q }
        titles.findAll { it == q }
    }
}
''')

        then:
        bookClass != null
    }

    void "test findPathTo detects a flattened query at argument index 1, not 0"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static Object toOther(Class other, String title) {
        String q = "MATCH (b:Book)-[*]->(o) WHERE b.title = ${title} RETURN o"
        findPathTo(other, q, [:])
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
        e.message.contains("passed to 'findPathTo'")
    }

    void "test a flattened variable referenced inside a nested closure still fails to compile"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static void byTitles(List titles) {
        titles.each { String title ->
            String q = "from Book where title = ${title}"
            executeQuery(q)
        }
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
    }

    void "test a field flattened via its own GString initializer warns but still compiles"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title
    String cachedQuery = "from Book where title = ${title}"

    List loadCached() {
        executeQuery(this.cachedQuery)
    }
}
''')

        then:
        warnings.size() == 1
        warnings[0].message.contains('GormUnsafeQueryString')
        warnings[0].message.contains("passed to 'executeQuery'")
    }

    void "test a field flattened via this.field assignment warns but still compiles"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title
    String cachedQuery

    void prepare(String title) {
        this.cachedQuery = "from Book where title = ${title}"
    }

    List loadCached() {
        executeQuery(this.cachedQuery)
    }
}
''')

        then:
        warnings.size() == 1
        warnings[0].message.contains('GormUnsafeQueryString')
    }

    void "test a field safely reassigned does not warn"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title
    String cachedQuery = "from Book"

    List loadCached() {
        this.cachedQuery = "from Book where title = :title"
        executeQuery(this.cachedQuery, [title: title])
    }
}
''')

        then:
        warnings.empty
    }

    void "test string concatenation with a non-constant value warns but still compiles"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        String q = "from Book where title = " + title
        executeQuery(q)
    }
}
''')

        then:
        warnings.size() == 1
        warnings[0].message.contains('GormUnsafeQueryString')
        warnings[0].message.contains("passed to 'executeQuery'")
    }

    void "test string concatenation passed directly as the query argument warns but still compiles"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        executeQuery("from Book where title = " + title)
    }
}
''')

        then:
        warnings.size() == 1
        warnings[0].message.contains('GormUnsafeQueryString')
    }

    void "test concatenating two constant strings does not warn"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List all() {
        String q = "from " + "Book"
        executeQuery(q)
    }
}
''')

        then:
        warnings.empty
    }

    void "test concatenating a GString with more text fails to compile as flattening, not as a lesser concatenation warning"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        String q = "from Book where title = ${title}" + " order by title"
        executeQuery(q)
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
        e.message.contains("passed to 'executeQuery'")
    }

    @Unroll
    void "test a GString whose interpolations are all constant text (#description) compiles cleanly with no warnings"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings("""
import grails.gorm.annotation.Entity

@Entity
class Book {
    static final String ORDER_BY = ' order by b.title'
    static final String DEFAULT_ORDER = ORDER_BY + ', b.id'
    String title

    static List search(Map params, boolean condition) {
        Map queryParams = [:]
        $declaration
        String q = "from Book b where 1 = 1 \${restriction}"
        executeQuery(q, queryParams)
    }
}
""")

        then:
        warnings.empty

        where:
        description                                  | declaration
        'a string literal'                           | 'String restriction = " and b.title = :title"'
        'a local reassigned in one branch'           | 'String restriction = ""\n        if (params.title) {\n            restriction = " and b.title = :title"\n            queryParams.title = params.title\n        }'
        'a local assigned in both branches'          | 'String restriction\n        if (params.title) {\n            restriction = " and b.title = :title"\n        } else {\n            restriction = ""\n        }'
        'a ternary over literals'                    | 'String restriction = params.title ? " and b.title = :title" : ""'
        'an Elvis over constant text'                | 'String base = condition ? null : " and b.title = :title"\n        String restriction = base ?: ""'
        'a static final field'                       | 'String restriction = ORDER_BY'
        'a qualified static final field'             | 'String restriction = Book.ORDER_BY'
        'a static final field built from another'    | 'String restriction = DEFAULT_ORDER'
        'a GString of constant text'                 | 'String restriction = " and b.title = ${":title"}"'
        'a concatenation of constant text'           | 'String restriction = " and " + "b.title = :title"'
        'an appended constant fragment'              | 'String restriction = ""\n        if (params.title) {\n            restriction += " and b.title = :title"\n        }'
        'a cast of constant text'                    | 'String restriction = (String) " and b.title = :title"'
        'toString() of constant text'                | 'String restriction = " and b.title = :title".toString()'
        'a two-hop alias of constant text'           | 'def first = " and b.title = :title"\n        def second = first\n        String restriction = second'
    }

    @Unroll
    void "test a GString interpolating #description is still flattened and fails to compile"() {
        when:
        new GroovyClassLoader().parseClass("""
import grails.gorm.annotation.Entity

@Entity
class Book {
    static String mutableFragment = ' order by b.title'
    static final String PREFIX = 'from Book b where '
    String title

    static List search(Map params, String title, boolean condition) {
        $declaration
        String q = "from Book b where 1 = 1 \${restriction}"
        executeQuery(q)
    }
}
""")

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')

        where:
        description                                    | declaration
        'a parameter'                                  | 'String restriction = title'
        'a local made non-constant in one branch'      | 'String restriction = ""\n        if (condition) {\n            restriction = title\n        }'
        'a local constant in only one branch'          | 'String restriction = title\n        if (condition) {\n            restriction = " and b.title = :title"\n        }'
        'a constant local appended with data'          | 'String restriction = " and b.title = "\n        restriction += title'
        'a ternary with a non-constant branch'         | 'String restriction = condition ? " and b.title = :title" : title'
        'a non-final static field'                     | 'String restriction = mutableFragment'
        'a local shadowing a static final constant'    | 'String PREFIX = title\n        String restriction = PREFIX'
        'a method call result'                         | 'String restriction = title.trim()'
        'a map value'                                  | 'String restriction = params.restriction'
        'an instance property'                         | 'String restriction = new Book().title'
    }

    void "test appending constant text with += and + to a constant query compiles cleanly with no warnings"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List search(Map params) {
        Map queryParams = [:]
        String order = " order by b.title"
        String q = "from Book b where 1 = 1"
        if (params.title) {
            q += " and b.title = :title"
            queryParams.title = params.title
        }
        q = q + order
        executeQuery(q, queryParams)
    }
}
''')

        then:
        warnings.empty
    }

    @Unroll
    void "test #description with += fails to compile as flattening"() {
        when:
        new GroovyClassLoader().parseClass("""
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        $declaration
        executeQuery(q)
    }
}
""")

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
        e.message.contains("passed to 'executeQuery'")

        where:
        description                               | declaration
        'appending text to a flattened query'     | 'String q = "from Book where title = ${title}"\n        q += " order by title"'
        'appending text to a live GString'        | 'def q = "from Book where title = ${title}"\n        q += " order by title"'
        'appending a live GString to constant text' | 'String q = "from Book where "\n        q += "title = ${title}"'
    }

    void "test concatenating more text onto an already-flattened query fails to compile rather than warning"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        String q = "from Book where title = ${title}"
        q = q + " order by title"
        executeQuery(q)
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
    }

    void "test a non-plus compound assignment stops a local from counting as constant text"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        def restriction = " and b.title = "
        restriction <<= title
        String q = "from Book b where 1 = 1 ${restriction}"
        executeQuery(q)
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
    }

    @Unroll
    void "test a flattened query suppressed on its declaration via #description compiles cleanly"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings("""
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        $annotation
        String q = "from Book where title = \${title}"
        executeQuery(q)
    }
}
""")

        then:
        warnings.empty

        where:
        description        | annotation
        'a single value'   | '@SuppressWarnings("GormUnsafeQueryString")'
        'a list of values' | '@SuppressWarnings(["unchecked", "GormUnsafeQueryString"])'
    }

    void "test a declaration-level suppression covers later reassignments of that variable"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List search(Map params) {
        @SuppressWarnings("GormUnsafeQueryString")
        String q = "from Book b where 1 = 1"
        if (params.title) {
            q = "${q} and b.title = ${params.title}"
        }
        q += params.order
        executeQuery(q)
    }
}
''')

        then:
        warnings.empty
    }

    void "test a declaration-level suppression on the fragment makes its interpolation constant text"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static String restrictionFor(Map params) {
        params.title ? " and b.title = :title" : ""
    }

    static List search(Map params) {
        @SuppressWarnings("GormUnsafeQueryString")
        String restriction = restrictionFor(params)
        String q = "from Book b where 1 = 1 ${restriction}"
        executeQuery(q, [title: params.title])
    }
}
''')

        then:
        warnings.empty
    }

    void "test a declaration-level suppression leaves every other variable checked"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        @SuppressWarnings("GormUnsafeQueryString")
        String reviewed = "from Book where title = ${title}"
        String q = "from Book where title = ${title}"
        executeQuery(q)
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
    }

    void "test redeclaring a suppressed name without the annotation re-arms the check"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title, boolean condition) {
        if (condition) {
            @SuppressWarnings("GormUnsafeQueryString")
            String q = "from Book where title = ${title}"
            executeQuery(q)
        }
        String q = "from Book where title = ${title}"
        executeQuery(q)
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('GormUnsafeQueryString')
    }

    void "test a declaration-level suppression also silences the concatenation warning for that variable"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        @SuppressWarnings("GormUnsafeQueryString")
        String q = "from Book where title = " + title
        executeQuery(q)
    }
}
''')

        then:
        warnings.empty
    }

    void "test a field suppressed on its declaration does not warn"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title
    @SuppressWarnings("GormUnsafeQueryString")
    String cachedQuery = "from Book where title = ${title}"

    List loadCached() {
        this.cachedQuery = "from Book where title = ${title}"
        executeQuery(this.cachedQuery)
    }
}
''')

        then:
        warnings.empty
    }

    void "test the error message explains the query text case and the declaration-level suppression"() {
        when:
        new GroovyClassLoader().parseClass('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        String q = "from Book where title = ${title}"
        executeQuery(q)
    }
}
''')

        then:
        def e = thrown(MultipleCompilationErrorsException)
        e.message.contains('HQL fragments')
        e.message.contains('GORM would bind the fragment as a parameter value')
        e.message.contains('to the declaration of the variable that holds the query text, or to the enclosing method')
    }

    void "test the concatenation warning points at the declaration-level suppression"() {
        when:
        List<WarningMessage> warnings = compileAndCollectWarnings('''
import grails.gorm.annotation.Entity

@Entity
class Book {
    String title

    static List byTitle(String title) {
        String q = "from Book where title = " + title
        executeQuery(q)
    }
}
''')

        then:
        warnings.size() == 1
        warnings[0].message.contains('to the declaration of the variable that holds the query text, or to the enclosing method')
    }
}
