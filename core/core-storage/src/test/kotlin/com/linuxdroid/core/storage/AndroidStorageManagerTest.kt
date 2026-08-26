package com.linuxdroid.core.storage

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.StorageAuthorizationError
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class AndroidStorageManagerTest {

    @Test
    fun `initial authorization state is Unknown`() {
        val context = mockk<Context>()
        val manager = AndroidStorageManager(context)
        assertThat(manager.authorizationState.value).isEqualTo(StorageAuthorizationState.Unknown)
        assertThat(manager.isAuthorized()).isFalse()
    }

    @Test
    fun `getAuthorizedLocation throws StorageAuthorizationError when unverified`() {
        val context = mockk<Context>()
        val manager = AndroidStorageManager(context)
        assertThrows(StorageAuthorizationError::class.java) {
            manager.getAuthorizedLocation()
        }
    }

    @Test
    fun `handleRevocation transitions state to Unauthorized safely`() {
        val context = mockk<Context>()
        val manager = AndroidStorageManager(context)
        manager.handleRevocation()
        assertThat(manager.isAuthorized()).isFalse()
        assertThat(manager.authorizationState.value).isInstanceOf(StorageAuthorizationState.Unauthorized::class.java)
    }
}

