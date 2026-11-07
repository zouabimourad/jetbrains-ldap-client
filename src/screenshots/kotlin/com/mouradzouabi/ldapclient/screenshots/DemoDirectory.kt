package com.mouradzouabi.ldapclient.screenshots

import com.mouradzouabi.ldapclient.core.PasswordHasher
import com.mouradzouabi.ldapclient.core.PasswordScheme
import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchEntry
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor
import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.schema.Schema
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/** Fake, deterministic directory used to produce Marketplace screenshots. */
class DemoDirectory : AutoCloseable {
    val server: InMemoryDirectoryServer
    val port: Int get() = server.listenPort

    init {
        val config = InMemoryDirectoryServerConfig(BASE)
        config.schema = Schema.mergeSchemas(Schema.getDefaultStandardSchema(), extraSchema())
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0))
        config.addInMemoryOperationInterceptor(SubordinatesInterceptor { dn -> server.countEntriesBelow(dn) - 1 })
        server = InMemoryDirectoryServer(config)
        server.addEntries(entries())
        server.startListening()
    }

    override fun close() = server.shutDown(true)

    /** The in-memory server does not compute numSubordinates; the browser uses it to hide expand handles on leaves. */
    private class SubordinatesInterceptor(private val count: (String) -> Int) : InMemoryOperationInterceptor() {
        override fun processSearchEntry(entry: InMemoryInterceptedSearchEntry) {
            val requested = entry.request.attributeList
            if (requested.none { it == "+" || it.equals("numSubordinates", true) || it.equals("hasSubordinates", true) }) return
            val original = entry.searchEntry
            val copy = Entry(original.dn, original.attributes)
            copy.setAttribute("numSubordinates", count(original.dn).toString())
            entry.setSearchEntry(copy)
        }
    }

    companion object {
        const val BASE = "dc=acme,dc=example"
        const val PEOPLE = "ou=people,$BASE"
        const val GROUPS = "ou=groups,$BASE"
        const val FEATURED_UID = "alice.martin"
        const val FEATURED_DN = "uid=$FEATURED_UID,$PEOPLE"
        const val ENGINEERING_DN = "cn=engineering,$GROUPS"
        const val USER_COUNT = 2400

        private val FIRST = listOf(
            "Amelia", "Andrew", "Anna", "Arthur", "Ava", "Benjamin", "Camille",
            "Charlotte", "Chloe", "Daniel", "David", "Elena", "Emily", "Emma", "Ethan", "Felix", "Gabriel", "Grace",
            "Hannah", "Hugo", "Isabella", "Jack", "James", "Julia", "Karim", "Laura", "Leo", "Lina", "Lucas", "Maya",
            "Mia", "Nathan", "Noah", "Nora", "Oliver", "Omar", "Paul", "Rachel", "Samuel", "Sara", "Sofia", "Thomas",
            "Victor", "Yasmine", "Zoe",
        )
        private val LAST = listOf(
            "Anderson", "Bernard", "Brown", "Chen", "Clark", "Dubois", "Durand", "Evans", "Garcia", "Haddad", "Hall",
            "Johnson", "Kim", "Lambert", "Lee", "Lopez", "Martin", "Meyer", "Miller", "Moreau", "Nguyen", "Novak",
            "Patel", "Petit", "Roux", "Rossi", "Schmidt", "Silva", "Smith", "Taylor", "Thomas", "Walker", "Weber",
            "White", "Wilson", "Young",
        )
        private val DEPARTMENTS = listOf("Engineering", "Sales", "Marketing", "Support", "Finance", "Operations")
        private val TITLES = mapOf(
            "Engineering" to listOf("Software Engineer", "Senior Software Engineer", "Staff Engineer", "SRE"),
            "Sales" to listOf("Account Executive", "Sales Manager"),
            "Marketing" to listOf("Product Marketing Manager", "Content Strategist"),
            "Support" to listOf("Support Engineer", "Customer Success Manager"),
            "Finance" to listOf("Accountant", "Financial Analyst"),
            "Operations" to listOf("Office Manager", "IT Administrator"),
        )
        private val CITIES = listOf("Paris", "Lyon", "Berlin", "London", "Montreal", "Tunis")

        private fun extraSchema() = Schema(
            Entry(
                "cn=schema",
                Attribute("objectClass", "top", "subschema"),
                Attribute(
                    "attributeTypes",
                    "( 1.3.6.1.1.1.1.0 NAME 'uidNumber' EQUALITY integerMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.27 SINGLE-VALUE )",
                    "( 1.3.6.1.1.1.1.1 NAME 'gidNumber' EQUALITY integerMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.27 SINGLE-VALUE )",
                    "( 1.3.6.1.1.1.1.3 NAME 'homeDirectory' EQUALITY caseExactIA5Match SYNTAX 1.3.6.1.4.1.1466.115.121.1.26 SINGLE-VALUE )",
                    "( 1.3.6.1.1.1.1.4 NAME 'loginShell' EQUALITY caseExactIA5Match SYNTAX 1.3.6.1.4.1.1466.115.121.1.26 SINGLE-VALUE )",
                    "( 1.3.6.1.1.1.1.12 NAME 'memberUid' EQUALITY caseExactIA5Match SYNTAX 1.3.6.1.4.1.1466.115.121.1.26 )",
                    "( 2.16.840.1.113730.3.1.198 NAME 'memberURL' SYNTAX 1.3.6.1.4.1.1466.115.121.1.26 )",
                ),
                Attribute(
                    "objectClasses",
                    "( 1.3.6.1.1.1.2.0 NAME 'posixAccount' SUP top AUXILIARY MUST ( cn \$ uid \$ uidNumber \$ gidNumber \$ homeDirectory ) MAY ( userPassword \$ loginShell \$ description ) )",
                    "( 1.3.6.1.1.1.2.2 NAME 'posixGroup' SUP top STRUCTURAL MUST ( cn \$ gidNumber ) MAY ( userPassword \$ memberUid \$ description ) )",
                    "( 2.16.840.1.113730.3.2.33 NAME 'groupOfURLs' SUP top STRUCTURAL MUST cn MAY ( memberURL \$ businessCategory \$ description \$ o \$ ou \$ owner \$ seeAlso ) )",
                ),
            ),
        )

        private data class Person(val uid: String, val first: String, val last: String, val department: String, val title: String, val city: String)

        private fun people(): List<Person> {
            val random = Random(42)
            val seen = HashSet<String>()
            val result = ArrayList<Person>()
            result += Person(FEATURED_UID, "Alice", "Martin", "Engineering", "Staff Engineer", "Paris")
            seen += FEATURED_UID
            while (result.size < USER_COUNT) {
                val first = FIRST.random(random)
                val last = LAST.random(random)
                val base = "${first.lowercase()}.${last.lowercase()}"
                var uid = base
                var n = 2
                while (!seen.add(uid)) uid = "$base${n++}"
                val department = DEPARTMENTS.random(random)
                result += Person(uid, first, last, department, TITLES.getValue(department).random(random), CITIES.random(random))
            }
            return result
        }

        private fun dn(p: Person) = "uid=${p.uid},$PEOPLE"

        private fun entries(): List<Entry> {
            val people = people()
            val list = ArrayList<Entry>()
            list += Entry(BASE, Attribute("objectClass", "top", "domain", "dcObject"), Attribute("dc", "acme"))
            list += ou("people", "All employees")
            list += ou("groups", "Static, nested, POSIX and dynamic groups")
            list += ou("services", "Technical accounts")

            val random = Random(7)
            people.forEachIndexed { index, p ->
                val entry = Entry(
                    dn(p),
                    Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson", "posixAccount"),
                    Attribute("uid", p.uid),
                    Attribute("cn", "${p.first} ${p.last}"),
                    Attribute("givenName", p.first),
                    Attribute("sn", p.last),
                    Attribute("displayName", "${p.first} ${p.last}"),
                    Attribute("mail", "${p.uid}@acme.example"),
                    Attribute("title", p.title),
                    Attribute("departmentNumber", p.department),
                    Attribute("l", p.city),
                    Attribute("employeeNumber", (10000 + index).toString()),
                    Attribute(
                        "telephoneNumber",
                        "+33 1 %02d %02d %02d %02d".format(random.nextInt(100), random.nextInt(100), random.nextInt(100), random.nextInt(100)),
                    ),
                    Attribute("uidNumber", (20000 + index).toString()),
                    Attribute("gidNumber", "5000"),
                    Attribute("homeDirectory", "/home/${p.uid}"),
                    Attribute("loginShell", "/bin/bash"),
                    Attribute("userPassword", PasswordHasher.encode("Secret-${p.uid}", PasswordScheme.SSHA)),
                )
                if (p.uid == FEATURED_UID) {
                    entry.addAttribute("manager", dn(people[1]))
                    entry.addAttribute("mobile", "+33 6 12 34 56 78")
                    entry.addAttribute("description", "Platform team lead, LDAP and SSO owner")
                    entry.addAttribute(Attribute("jpegPhoto", avatar("AM")))
                } else {
                    entry.addAttribute("manager", if (p.department == "Engineering") FEATURED_DN else dn(people[1]))
                }
                list += entry
            }

            fun members(department: String, limit: Int) =
                people.filter { it.department == department && it.uid != FEATURED_UID }.take(limit).map(::dn)

            list += groupOfNames("backend-team", "Backend developers", listOf(FEATURED_DN) + members("Engineering", 12))
            list += groupOfNames("frontend-team", "Frontend developers", members("Engineering", 30).drop(12))
            list += groupOfNames(
                "engineering", "Engineering department",
                listOf("cn=backend-team,$GROUPS", "cn=frontend-team,$GROUPS") + members("Engineering", 60).drop(30),
            )
            list += groupOfNames("sales", "Sales department", members("Sales", 40))
            list += groupOfNames("marketing", "Marketing department", members("Marketing", 25))
            list += groupOfNames("support", "Customer support", members("Support", 30))
            list += groupOfNames(
                "all-staff", "Everybody",
                listOf(ENGINEERING_DN, "cn=sales,$GROUPS", "cn=marketing,$GROUPS", "cn=support,$GROUPS"),
            )
            list += groupOfNames("ldap-admins", "Directory administrators", listOf(FEATURED_DN, dn(people[3])))
            list += Entry(
                "cn=vpn-users,$GROUPS",
                Attribute("objectClass", "top", "groupOfUniqueNames"),
                Attribute("cn", "vpn-users"),
                Attribute("description", "Remote access"),
                Attribute("uniqueMember", listOf(FEATURED_DN) + people.drop(5).take(20).map(::dn)),
            )
            list += Entry(
                "cn=developers,$GROUPS",
                Attribute("objectClass", "top", "posixGroup"),
                Attribute("cn", "developers"),
                Attribute("gidNumber", "5001"),
                Attribute("memberUid", listOf(FEATURED_UID) + people.filter { it.department == "Engineering" && it.uid != FEATURED_UID }.take(15).map { it.uid }),
            )
            list += Entry(
                "cn=paris-office,$GROUPS",
                Attribute("objectClass", "top", "groupOfURLs"),
                Attribute("cn", "paris-office"),
                Attribute("description", "Dynamic group: everybody located in Paris"),
                Attribute("memberURL", "ldap:///$PEOPLE??sub?(l=Paris)"),
            )

            listOf("jenkins" to "CI server", "gitlab" to "Source code hosting", "grafana" to "Monitoring dashboards").forEach { (cn, description) ->
                list += Entry(
                    "cn=$cn,ou=services,$BASE",
                    Attribute("objectClass", "top", "applicationProcess"),
                    Attribute("cn", cn),
                    Attribute("description", description),
                )
            }
            return list
        }

        private fun ou(name: String, description: String) = Entry(
            "ou=$name,$BASE",
            Attribute("objectClass", "top", "organizationalUnit"),
            Attribute("ou", name),
            Attribute("description", description),
        )

        private fun groupOfNames(cn: String, description: String, members: List<String>) = Entry(
            "cn=$cn,$GROUPS",
            Attribute("objectClass", "top", "groupOfNames"),
            Attribute("cn", cn),
            Attribute("description", description),
            Attribute("member", members),
        )

        /** Small generated profile picture, so that the binary/image editor has something to show. */
        private fun avatar(initials: String): ByteArray {
            val size = 160
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0xF2F4F7)
            g.fillRect(0, 0, size, size)
            g.paint = GradientPaint(0f, 0f, Color(0x3574F0), size.toFloat(), size.toFloat(), Color(0x7B5CF0))
            g.fillOval(8, 8, size - 16, size - 16)
            g.stroke = BasicStroke(4f)
            g.color = Color(255, 255, 255, 90)
            g.drawOval(10, 10, size - 20, size - 20)
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 60)
            val metrics = g.fontMetrics
            g.drawString(initials, (size - metrics.stringWidth(initials)) / 2, (size - metrics.height) / 2 + metrics.ascent)
            g.dispose()
            return ByteArrayOutputStream().use { out ->
                ImageIO.write(image, "jpg", out)
                out.toByteArray()
            }
        }
    }
}
