/*
 * Copyright 2018 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// code was taken from
// https://github.com/android/uamp/blob/main/common/src/main/java/com/example/android/uamp/media/PackageValidator.kt
// and modified

package de.moleman1024.audiowagon.authorization

import android.Manifest.permission.MEDIA_CONTENT_CONTROL
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.os.Process
import androidx.annotation.XmlRes
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.AudioBrowserService
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import kotlin.collections.LinkedHashMap

private const val TAG = "PkgValidation"
private val logger = Logger
private const val ANDROID_PLATFORM = "android"
private val WHITESPACE_REGEX = "\\s|\\n".toRegex()

/**
 * Validates other packages that try to access the MediaBrowser of the app (and blocks them if necessary)
 */
class PackageValidation(context: Context, @XmlRes xmlResId: Int) {
    private val packageManager: PackageManager = context.packageManager
    private val certificateAllowList: Map<String, KnownCallerInfo>
    private val platformSignature: String
    private val callerChecked = mutableMapOf<String, Pair<Int, Boolean>>()

    init {
        val parser = context.resources.getXml(xmlResId)
        certificateAllowList = buildCertificateAllowList(parser)
        platformSignature = getSystemSignature()
    }

    /**
     * Checks whether the caller attempting to connect to a [AudioBrowserService] is known.
     * See [AudioBrowserService.onGetRoot] for where this is utilized.
     *
     * @param callingPackage The package name of the caller.
     * @param callingUid The user id of the caller.
     * @return `true` if the caller is known, `false` otherwise.
     */
    fun isClientValid(callingPackage: String, callingUid: Int): Boolean {
        logger.debug(TAG, "isClientValid(callingPkg=$callingPackage, callingUid=$callingUid)")
        // If the caller has already been checked, return the previous result here.
        val (checkedUid, checkResult) = callerChecked[callingPackage] ?: Pair(0, false)
        if (checkedUid == callingUid) {
            return checkResult
        }
        /**
         * Because some of these checks can be slow, we save the results in [callerChecked] after
         * this code is run.
         *
         * In particular, there's little reason to recompute the calling package's certificate
         * signature (SHA-256) each call.
         *
         * This is safe to do as we know the UID matches the package's UID (from the check above),
         * and app UIDs are set at install time. Additionally, a package name + UID is guaranteed to
         * be constant until a reboot. (After a reboot then a previously assigned UID could be
         * reassigned.)
         */
        val callerPackageInfo = buildCallerInfo(callingPackage)
            ?: throw IllegalStateException("Caller wasn't found in the system?")
        // Verify that things aren't ... broken. (This test should always pass.)
        check(callerPackageInfo.uid == callingUid) {
            "Caller's package UID doesn't match caller's actual UID?"
        }
        val callerSignature = callerPackageInfo.signature
        val isPackageInAllowList: Boolean
        try {
            isPackageInAllowList = certificateAllowList[callingPackage]?.signatures?.first {
                it.signature == callerSignature
            } != null
        } catch (exc: NoSuchElementException) {
            logger.exception(TAG, "Caller signature not found $callerSignature: ${exc.message.toString()}", exc)
            return false
        }
        val isCallerKnown = when {
            // If it's our own app making the call, allow it.
            callingUid == Process.myUid() -> true
            // If it's one of the apps on the allow list, allow it.
            isPackageInAllowList -> true
            // If the system is making the call, allow it.
            callingUid == Process.SYSTEM_UID -> true
            // If the app was signed by the same certificate as the platform itself, also allow it.
            callerSignature == platformSignature -> true
            /**
             * [MEDIA_CONTENT_CONTROL] permission is only available to system applications, and
             * while it isn't required to allow these apps to connect to a
             * [AudioBrowserService], allowing this ensures optimal compatability with apps
             * such as Google Assistant.
             */
            callerPackageInfo.permissions.contains(MEDIA_CONTENT_CONTROL) -> true
            // If none of the previous checks succeeded, then the caller is unrecognized.
            else -> false
        }
        if (!isCallerKnown) {
            logUnknownCaller(callerPackageInfo)
        }
        // Save our work for next time.
        callerChecked[callingPackage] = Pair(callingUid, isCallerKnown)
        return isCallerKnown
    }

    /**
     * Logs an info level message with details of how to add a caller to the allowed callers list
     * when the app is debuggable.
     */
    private fun logUnknownCaller(callerPackageInfo: CallerPackageInfo) {
        callerPackageInfo.signature?.let {
            val formattedLog = "need to allow this caller in xml file: $callerPackageInfo"
            logger.warning(TAG, formattedLog)
        }
    }

    /**
     * Builds a [CallerPackageInfo] for a given package that can be used for all the
     * various checks that are performed before allowing an app to connect to a
     * [AudioBrowserService].
     */
    private fun buildCallerInfo(callingPackage: String): CallerPackageInfo? {
        val packageInfo = getPackageInfo(callingPackage) ?: return null
        val appName = packageInfo.applicationInfo?.loadLabel(packageManager).toString()
        val uid = packageInfo.applicationInfo?.uid
        val signature = getSignature(packageInfo)
        val requestedPermissions = packageInfo.requestedPermissions
        val permissionFlags = packageInfo.requestedPermissionsFlags
        val activePermissions = mutableSetOf<String>()
        requestedPermissions?.forEachIndexed { index, permission ->
            if (permissionFlags?.get(index)?.and(REQUESTED_PERMISSION_GRANTED) != 0) {
                activePermissions += permission
            }
        }
        return CallerPackageInfo(appName, callingPackage, uid, signature, activePermissions.toSet())
    }

    /**
     * Looks up the [PackageInfo] for a package name.
     * This requests both the signatures (for checking if an app is on the allow list) and
     * the app's permissions, which allow for more flexibility in the allow list.
     *
     * @return [PackageInfo] for the package name or null if it's not found.
     */
    private fun getPackageInfo(callingPackage: String): PackageInfo? =
        packageManager.getPackageInfo(
            callingPackage, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
        )

    /**
     * Gets the signature of a given package's [PackageInfo].
     *
     * The "signature" is a SHA-256 hash of the public key of the signing certificate used by
     * the app.
     *
     * If the app is not found, or if the app does not have exactly one signature, this method
     * returns `null` as the signature.
     */
    private fun getSignature(packageInfo: PackageInfo): String? {
        logger.debug(TAG, "getSignature(${packageInfo.packageName})")
        return if (packageInfo.signingInfo == null
            || packageInfo.signingInfo!!.signingCertificateHistory == null
            || packageInfo.signingInfo!!.signingCertificateHistory.size != 1) {
            // Security best practices dictate that an app should be signed with exactly one (1)
            // signature. Because of this, if there are multiple signatures, reject it.
            null
        } else {
            val certificate = packageInfo.signingInfo!!.signingCertificateHistory[0].toByteArray()
            getSignatureSha256(certificate)
        }
    }

    private fun buildCertificateAllowList(parser: XmlResourceParser): Map<String, KnownCallerInfo> {
        val certificateAllowList = LinkedHashMap<String, KnownCallerInfo>()
        try {
            var eventType = parser.next()
            while (eventType != XmlResourceParser.END_DOCUMENT) {
                if (eventType == XmlResourceParser.START_TAG) {
                    val callerInfo = when (parser.name) {
                        "signature" -> parseV2Tag(parser)
                        else -> null
                    }
                    callerInfo?.let { info ->
                        val packageName = info.packageName
                        val existingCallerInfo = certificateAllowList[packageName]
                        if (existingCallerInfo != null) {
                            existingCallerInfo.signatures += callerInfo.signatures
                        } else {
                            certificateAllowList[packageName] = callerInfo
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (xmlException: XmlPullParserException) {
            logger.exception(TAG, "Could not read allowed callers from XML", xmlException)
        } catch (ioException: IOException) {
            logger.exception(TAG, "Could not read allowed callers from XML", ioException)
        }
        return certificateAllowList
    }

    /**
     * Parses a v2 format tag. See allowed_media_browser_callers.xml for more details.
     */
    private fun parseV2Tag(parser: XmlResourceParser): KnownCallerInfo {
        val name = parser.getAttributeValue(null, "name")
        val packageName = parser.getAttributeValue(null, "package")
        val callerSignatures = mutableSetOf<KnownSignature>()
        var eventType = parser.next()
        while (eventType != XmlResourceParser.END_TAG) {
            val isRelease = parser.getAttributeBooleanValue(null, "release", false)
            val signature = parser.nextText().replace(WHITESPACE_REGEX, "").lowercase(Locale.getDefault())
            callerSignatures += KnownSignature(signature, isRelease)
            eventType = parser.next()
        }
        return KnownCallerInfo(name, packageName, callerSignatures)
    }

    /**
     * Finds the Android platform signing key signature. This key is never null.
     */
    private fun getSystemSignature(): String =
        getPackageInfo(ANDROID_PLATFORM)?.let { platformInfo ->
            getSignature(platformInfo)
        } ?: throw IllegalStateException("Platform signature not found")

    /**
     * Creates a SHA-256 signature given a certificate byte array.
     */
    private fun getSignatureSha256(certificate: ByteArray): String {
        val md: MessageDigest
        try {
            md = MessageDigest.getInstance("SHA256")
        } catch (noSuchAlgorithmException: NoSuchAlgorithmException) {
            logger.error(TAG, "No such algorithm: $noSuchAlgorithmException")
            throw RuntimeException("Could not find SHA256 hash algorithm", noSuchAlgorithmException)
        }
        md.update(certificate)
        // This code takes the byte array generated by `md.digest()` and joins each of the bytes
        // to a string, applying the string format `%02x` on each digit before it's appended, with
        // a colon (':') between each of the items.
        // For example: input=[0,2,4,6,8,10,12], output="00:02:04:06:08:0a:0c"
        return md.digest().joinToString(":") { String.format("%02x", it) }
    }

    private data class KnownCallerInfo(
        val name: String,
        val packageName: String,
        val signatures: MutableSet<KnownSignature>
    )

    private data class KnownSignature(
        val signature: String,
        val release: Boolean
    )

    /**
     * Convenience class to hold all of the information about an app that's being checked
     * to see if it's a known caller.
     */
    private data class CallerPackageInfo(
        val name: String,
        val packageName: String,
        val uid: Int?,
        val signature: String?,
        val permissions: Set<String>
    )
}
