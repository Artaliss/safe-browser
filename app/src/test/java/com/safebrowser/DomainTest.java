package com.safebrowser;

import static org.junit.Assert.*;
import org.junit.Test;

public class DomainTest {
    @Test public void baseDomain() {
        assertEquals("example.com", MainActivity.baseDomain("m.www.example.com"));
        assertEquals("sanook.co.th", MainActivity.baseDomain("news.sanook.co.th"));
        assertEquals("google.co.th", MainActivity.baseDomain("www.google.co.th"));
        assertTrue(MainActivity.sameSite("a.example.com", "b.example.com"));
        assertFalse(MainActivity.sameSite("example.com", "evil-ads.net"));
        assertTrue(MainActivity.isGoogle("www.google.com"));
    }
}
