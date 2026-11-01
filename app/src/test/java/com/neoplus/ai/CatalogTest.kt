package com.neoplus.ai

import com.neoplus.ai.integration.AppCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    @Test fun packagesAreUnique() {
        val pkgs = AppCatalog.apps.map { it.packageName }
        assertEquals(pkgs.size, pkgs.toSet().size)
    }
    @Test fun everyAppExplainsAccess() {
        assertTrue(AppCatalog.apps.all { it.how.isNotBlank() })
    }
}
