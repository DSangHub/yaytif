package com.yaytif.app.release

import com.yaytif.app.domain.MarketConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Release readiness & privacy guardrail audit tests verified during ./gradlew testDebugUnitTest:
 * 1. Exported Room database schema (v1) integrity and TEXT affinity for monetary fields.
 * 2. Cloud backup & device-transfer exclusion rules for strict on-device privacy.
 * 3. AndroidManifest RTL + 13-locale config consistency with MarketConfig & strings.xml files.
 * 4. Release R8/ProGuard keep rules and debug Log stripping coverage.
 */
class ReleaseHardeningAndPrivacyAuditTest {

    private val appDir: File by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        when {
            File(cwd, "src/main/AndroidManifest.xml").isFile -> cwd
            File(cwd, "app/src/main/AndroidManifest.xml").isFile -> File(cwd, "app")
            else -> error("Unable to locate Android :app module directory from ${cwd.absolutePath}")
        }
    }

    @Test
    fun `exported Room schema v1 matches local_notifications and sponsored_alerts invariants`() {
        val schemaFile = File(appDir, "schemas/com.yaytif.app.data.local.YaytifDatabase/1.json")
        assertTrue("Expected exported Room schema at ${schemaFile.path}", schemaFile.isFile)

        val root = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(1, database.getValue("version").jsonPrimitive.int)

        val entitiesByTable = database.getValue("entities").jsonArray.associateBy {
            it.jsonObject.getValue("tableName").jsonPrimitive.content
        }
        assertEquals(setOf("local_notifications", "sponsored_alerts"), entitiesByTable.keys)

        // Verify local_notifications has unique notification_key index and expected columns
        val localNotif = entitiesByTable.getValue("local_notifications").jsonObject
        val localColumns = localNotif.getValue("fields").jsonArray.associate { field ->
            val obj = field.jsonObject
            obj.getValue("columnName").jsonPrimitive.content to obj.getValue("affinity").jsonPrimitive.content
        }
        assertEquals("INTEGER", localColumns["id"])
        assertEquals("TEXT", localColumns["notification_key"])
        assertEquals("TEXT", localColumns["package_name"])
        assertEquals("TEXT", localColumns["title"])
        assertEquals("TEXT", localColumns["body"])
        assertEquals("TEXT", localColumns["sender_name"])

        val uniqueKeyIndex = localNotif.getValue("indices").jsonArray.firstOrNull { idx ->
            idx.jsonObject.getValue("columnNames").jsonArray
                .map { it.jsonPrimitive.content } == listOf("notification_key")
        }
        assertNotNull("local_notifications must have an index on notification_key", uniqueKeyIndex)
        assertTrue(
            "local_notifications notification_key index must be unique",
            uniqueKeyIndex!!.jsonObject.getValue("unique").jsonPrimitive.boolean
        )

        // Verify sponsored_alerts stores all monetary values as TEXT (never REAL/FLOAT)
        val sponsored = entitiesByTable.getValue("sponsored_alerts").jsonObject
        val sponsoredColumns = sponsored.getValue("fields").jsonArray.associate { field ->
            val obj = field.jsonObject
            obj.getValue("columnName").jsonPrimitive.content to obj.getValue("affinity").jsonPrimitive.content
        }
        assertEquals("TEXT", sponsoredColumns["payout_per_minute"])
        assertEquals("TEXT", sponsoredColumns["commission_share"])
        assertEquals("TEXT", sponsoredColumns["budget_remaining"])
        assertTrue(
            "No column in sponsored_alerts may use floating-point REAL affinity",
            sponsoredColumns.values.none { it.equals("REAL", ignoreCase = true) }
        )
    }

    @Test
    fun `backup and data extraction rules exclude local Room database and preferences`() {
        val dataExtractionFile = File(appDir, "src/main/res/xml/data_extraction_rules.xml")
        val backupRulesFile = File(appDir, "src/main/res/xml/backup_rules.xml")
        assertTrue(dataExtractionFile.isFile)
        assertTrue(backupRulesFile.isFile)

        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val extractionDoc = dbf.newDocumentBuilder().parse(dataExtractionFile)

        for (section in listOf("cloud-backup", "device-transfer")) {
            val sectionNodes = extractionDoc.getElementsByTagName(section)
            assertEquals("Expected <$section> block", 1, sectionNodes.length)
            val excludes = mutableSetOf<String>()
            val children = sectionNodes.item(0).childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeName == "exclude") {
                    excludes += node.attributes.getNamedItem("domain").nodeValue
                }
            }
            assertTrue("<$section> must exclude database", "database" in excludes)
            assertTrue("<$section> must exclude sharedpref", "sharedpref" in excludes)
            assertTrue("<$section> must exclude file", "file" in excludes)
        }

        val backupDoc = dbf.newDocumentBuilder().parse(backupRulesFile)
        val fullExcludes = mutableSetOf<String>()
        val excludeNodes = backupDoc.getElementsByTagName("exclude")
        for (i in 0 until excludeNodes.length) {
            fullExcludes += excludeNodes.item(i).attributes.getNamedItem("domain").nodeValue
        }
        assertTrue("backup_rules.xml must exclude database", "database" in fullExcludes)
        assertTrue("backup_rules.xml must exclude sharedpref", "sharedpref" in fullExcludes)
    }

    @Test
    fun `manifest RTL and 13-locale config match MarketConfig and resource directories`() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

        val manifestFile = File(appDir, "src/main/AndroidManifest.xml")
        val manifestDoc = dbf.newDocumentBuilder().parse(manifestFile)
        val appElement = manifestDoc.getElementsByTagName("application").item(0)
        assertNotNull(appElement)
        val attrs = appElement.attributes

        assertEquals("true", attrs.getNamedItemNS(androidNs, "supportsRtl")?.nodeValue)
        assertEquals("false", attrs.getNamedItemNS(androidNs, "allowBackup")?.nodeValue)
        assertEquals("@xml/locales_config", attrs.getNamedItemNS(androidNs, "localeConfig")?.nodeValue)
        assertEquals(
            "@xml/data_extraction_rules",
            attrs.getNamedItemNS(androidNs, "dataExtractionRules")?.nodeValue
        )

        val localesFile = File(appDir, "src/main/res/xml/locales_config.xml")
        val localesDoc = dbf.newDocumentBuilder().parse(localesFile)
        val localeNodes = localesDoc.getElementsByTagName("locale")
        val xmlLocales = (0 until localeNodes.length).map { idx ->
            localeNodes.item(idx).attributes.getNamedItemNS(androidNs, "name").nodeValue
        }
        val expectedLocales = MarketConfig.SUPPORTED_LOCALES.map { it.languageTag }
        assertEquals(13, xmlLocales.size)
        assertEquals(expectedLocales, xmlLocales)

        val resDir = File(appDir, "src/main/res")
        for (tag in xmlLocales) {
            val folder = when (tag) {
                "en" -> "values"
                "pt-BR" -> "values-pt-rBR"
                else -> "values-$tag"
            }
            val stringsFile = File(resDir, "$folder/strings.xml")
            assertTrue("Missing strings.xml for locale $tag at ${stringsFile.path}", stringsFile.isFile)
        }
    }

    @Test
    fun `release ProGuard rules cover DTOs, BigDecimalSerializer, Room, WorkManager, services, and Log stripping`() {
        val proguardFile = File(appDir, "proguard-rules.pro")
        assertTrue("Missing proguard-rules.pro", proguardFile.isFile)
        val rules = proguardFile.readText()

        val requiredClauses = listOf(
            "com.yaytif.app.data.remote.dto.**",
            "com.yaytif.app.data.remote.BigDecimalSerializer",
            "com.yaytif.app.data.remote.YaytifApiService",
            "com.yaytif.app.data.local.entity.**",
            "com.yaytif.app.data.local.dao.**",
            "com.yaytif.app.worker.**",
            "NotificationListenerService",
            "SponsoredOverlayService",
            "-assumenosideeffects class android.util.Log"
        )
        for (clause in requiredClauses) {
            assertTrue(
                "proguard-rules.pro must include keep/hardening rule for '$clause'",
                rules.contains(clause)
            )
        }
    }
}
