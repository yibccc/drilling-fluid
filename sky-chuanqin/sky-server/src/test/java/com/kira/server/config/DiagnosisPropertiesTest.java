package com.kira.server.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisPropertiesTest {

    @Test
    void testDefaultValues() {
        DiagnosisProperties properties = new DiagnosisProperties();

        // Test default values
        assertEquals(15, properties.getAlertCacheTtl());
        assertEquals(15, properties.getResultCacheTtl());
        assertEquals(5, properties.getTimeoutMinutes());
        assertTrue(properties.isEnabled());
    }

    @Test
    void testSettersAndGetters() {
        DiagnosisProperties properties = new DiagnosisProperties();

        // Test setters
        properties.setAlertCacheTtl(30);
        properties.setResultCacheTtl(60);
        properties.setTimeoutMinutes(10);
        properties.setEnabled(false);

        // Test getters
        assertEquals(30, properties.getAlertCacheTtl());
        assertEquals(60, properties.getResultCacheTtl());
        assertEquals(10, properties.getTimeoutMinutes());
        assertEquals(false, properties.isEnabled());
    }
}
