package com.example.cryptoapp;

import com.example.cryptoapp.Utils.SystemFileManagerLauncher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SystemFileManagerLauncherTest {
    @Test public void targetsAndroidDataDocumentId() {
        assertEquals("primary:Android/data", SystemFileManagerLauncher.targetDocumentId());
    }
}
