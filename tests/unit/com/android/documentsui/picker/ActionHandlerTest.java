/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.documentsui.picker;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Path;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.documentsui.R;
import com.android.documentsui.base.DocumentStack;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.base.Shared;
import com.android.documentsui.base.State;
import com.android.documentsui.base.State.ActionType;
import com.android.documentsui.testing.DocumentStackAsserts;
import com.android.documentsui.testing.TestEnv;
import com.android.documentsui.testing.TestRootsAccess;
import com.android.documentsui.testing.TestLastAccessedStorage;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class ActionHandlerTest {

    private TestEnv mEnv;
    private TestActivity mActivity;
    private ActionHandler<TestActivity> mHandler;
    private TestLastAccessedStorage mLastAccessed;

    @Before
    public void setUp() {
        mEnv = TestEnv.create();
        mActivity = TestActivity.create(mEnv);
        mEnv.roots.configurePm(mActivity.packageMgr);
        mLastAccessed = new TestLastAccessedStorage();

        mHandler = new ActionHandler<>(
                mActivity,
                mEnv.state,
                mEnv.roots,
                mEnv.docs,
                mEnv.searchViewManager,
                mEnv::lookupExecutor,
                mEnv.injector,
                mLastAccessed
        );

        mEnv.dialogs.confirmNext();

        mEnv.selectionMgr.toggleSelection("1");

        AsyncTask.setDefaultExecutor(mEnv.mExecutor);
    }

    @AfterClass
    public static void tearDownOnce() {
        AsyncTask.setDefaultExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Test
    public void testInitLocation_CopyDestination_DefaultsToDownloads() throws Exception {
        mActivity.resources.bools.put(R.bool.show_documents_root, false);

        Intent intent = mActivity.getIntent();
        intent.setAction(Shared.ACTION_PICK_COPY_DESTINATION);
        mHandler.initLocation(mActivity.getIntent());
        assertRootPicked(TestRootsAccess.DOWNLOADS.getUri());
    }

    @Test
    public void testInitLocation_CopyDestination_DocumentsRootEnabled() throws Exception {
        mActivity.resources.bools.put(R.bool.show_documents_root, true);

        Intent intent = mActivity.getIntent();
        intent.setAction(Shared.ACTION_PICK_COPY_DESTINATION);
        mHandler.initLocation(intent);
        assertRootPicked(TestRootsAccess.HOME.getUri());
    }

    @Test
    public void testInitLocation_LaunchToDocuments() throws Exception {
        mEnv.docs.nextIsDocumentsUri = true;
        mEnv.docs.nextPath = new Path(
                TestRootsAccess.HOME.rootId,
                Arrays.asList(
                        TestEnv.FOLDER_0.documentId,
                        TestEnv.FOLDER_1.documentId,
                        TestEnv.FILE_GIF.documentId));
        mEnv.docs.nextDocuments =
                Arrays.asList(TestEnv.FOLDER_0, TestEnv.FOLDER_1, TestEnv.FILE_GIF);

        mActivity.refreshCurrentRootAndDirectory.assertNotCalled();
        Intent intent = mActivity.getIntent();
        intent.setAction(Intent.ACTION_GET_CONTENT);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, TestEnv.FILE_GIF.derivedUri);
        mHandler.initLocation(intent);

        mEnv.beforeAsserts();

        DocumentStackAsserts.assertEqualsTo(mEnv.state.stack, TestRootsAccess.HOME,
                Arrays.asList(TestEnv.FOLDER_0, TestEnv.FOLDER_1));
        mActivity.refreshCurrentRootAndDirectory.assertCalled();
    }

    @Test
    public void testInitLocation_RestoresLastAccessedStack() throws Exception {
        final DocumentStack stack =
                new DocumentStack(TestRootsAccess.HAMMY, TestEnv.FOLDER_0, TestEnv.FOLDER_1);
        mLastAccessed.setLastAccessed(mActivity, stack);

        mHandler.initLocation(mActivity.getIntent());

        mEnv.beforeAsserts();
        assertEquals(stack, mEnv.state.stack);
        mActivity.refreshCurrentRootAndDirectory.assertCalled();
    }

    @Test
    public void testInitLocation_DefaultToRecents_ActionGetContent() throws Exception {
        testInitLocationDefaultToRecentsOnAction(State.ACTION_GET_CONTENT);
    }

    @Test
    public void testInitLocation_DefaultToRecents_ActionOpen() throws Exception {
        testInitLocationDefaultToRecentsOnAction(State.ACTION_OPEN);
    }

    @Test
    public void testInitLocation_DefaultToRecents_ActionOpenTree() throws Exception {
        testInitLocationDefaultToRecentsOnAction(State.ACTION_OPEN_TREE);
    }

    @Test
    public void testInitLocation_DefaultsToDownloads_ActionCreate() throws Exception {
        mEnv.state.action = State.ACTION_CREATE;
        mActivity.resources.bools.put(R.bool.show_documents_root, false);

        mActivity.refreshCurrentRootAndDirectory.assertNotCalled();

        mHandler.initLocation(mActivity.getIntent());

        assertRootPicked(TestRootsAccess.DOWNLOADS.getUri());
    }

    @Test
    public void testOpenContainerDocument() {
        mHandler.openContainerDocument(TestEnv.FOLDER_0);

        assertEquals(TestEnv.FOLDER_0, mEnv.state.stack.peek());

        mActivity.refreshCurrentRootAndDirectory.assertCalled();
    }

    @Test
    public void testPickDocument_SetsCorrectResultAndFinishes_ActionPickCopyDestination()
            throws Exception {

        mEnv.state.action = State.ACTION_PICK_COPY_DESTINATION;
        mEnv.state.stack.changeRoot(TestRootsAccess.HOME);
        mEnv.state.stack.push(TestEnv.FOLDER_1);
        mEnv.state.stack.push(TestEnv.FOLDER_2);

        mActivity.finishedHandler.assertNotCalled();

        mHandler.pickDocument(TestEnv.FOLDER_2);

        mEnv.beforeAsserts();

        assertLastAccessedStackUpdated();

        assertEquals(Activity.RESULT_OK, (long) mActivity.setResult.getLastValue().first);
        final Intent result = mActivity.setResult.getLastValue().second;
        assertPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION, false);
        assertPermission(result, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, false);
        assertPermission(result, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, false);
        assertPermission(result, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, false);
        assertContent(result, TestEnv.FOLDER_2.derivedUri);

        mActivity.finishedHandler.assertCalled();
    }

    @Test
    public void testPickDocument_SetsCorrectResultAndFinishes_ActionOpenTree() throws Exception {
        mEnv.state.action = State.ACTION_OPEN_TREE;
        mEnv.state.stack.changeRoot(TestRootsAccess.HOME);
        mEnv.state.stack.push(TestEnv.FOLDER_1);
        mEnv.state.stack.push(TestEnv.FOLDER_2);

        mActivity.finishedHandler.assertNotCalled();

        mHandler.pickDocument(TestEnv.FOLDER_2);

        mEnv.beforeAsserts();

        assertLastAccessedStackUpdated();

        assertEquals(Activity.RESULT_OK, (long) mActivity.setResult.getLastValue().first);
        final Intent result = mActivity.setResult.getLastValue().second;
        assertPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, true);
        assertContent(result, DocumentsContract.buildTreeDocumentUri(
                TestRootsAccess.HOME.authority, TestEnv.FOLDER_2.documentId));

        mActivity.finishedHandler.assertCalled();
    }

    @Test
    public void testSaveDocument_SetsCorrectResultAndFinishes() throws Exception {
        mEnv.state.action = State.ACTION_CREATE;
        mEnv.state.stack.changeRoot(TestRootsAccess.HOME);
        mEnv.state.stack.push(TestEnv.FOLDER_1);

        final String mimeType = "audio/aac";
        final String displayName = "foobar.m4a";

        mHandler.saveDocument(mimeType, displayName, (boolean inProgress) -> {});

        mEnv.beforeAsserts();

        mEnv.docs.assertCreatedDocument(TestEnv.FOLDER_1, mimeType, displayName);
        final Uri docUri = mEnv.docs.getLastCreatedDocumentUri();

        assertLastAccessedStackUpdated();

        assertEquals(Activity.RESULT_OK, (long) mActivity.setResult.getLastValue().first);
        final Intent result = mActivity.setResult.getLastValue().second;
        assertPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, false);
        assertContent(result, docUri);

        mActivity.finishedHandler.assertCalled();
    }

    @Test
    public void testSaveDocument_ConfirmsOverwrite() {
        mEnv.state.action = State.ACTION_CREATE;
        mEnv.state.stack.changeRoot(TestRootsAccess.HOME);
        mEnv.state.stack.push(TestEnv.FOLDER_1);

        mHandler.saveDocument(null, TestEnv.FILE_JPG);

        mEnv.dialogs.assertOverwriteConfirmed(TestEnv.FILE_JPG);
    }

    @Test
    public void testFinishPicking_SetsCorrectResultAndFinishes_ActionGetContent() throws Exception {
        mEnv.state.action = State.ACTION_GET_CONTENT;
        mEnv.state.stack.changeRoot(TestRootsAccess.HOME);
        mEnv.state.stack.push(TestEnv.FOLDER_1);

        mActivity.finishedHandler.assertNotCalled();

        mHandler.finishPicking(TestEnv.FILE_JPG.derivedUri);

        mEnv.beforeAsserts();

        assertLastAccessedStackUpdated();

        assertEquals(Activity.RESULT_OK, (long) mActivity.setResult.getLastValue().first);
        final Intent result = mActivity.setResult.getLastValue().second;
        assertPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, false);
        assertPermission(result, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, false);
        assertPermission(result, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, false);
        assertContent(result, TestEnv.FILE_JPG.derivedUri);

        mActivity.finishedHandler.assertCalled();
    }

    @Test
    public void testFinishPicking_SetsCorrectResultAndFinishes_ActionGetContent_MultipleSelection()
            throws Exception {
        mEnv.state.action = State.ACTION_GET_CONTENT;
        mEnv.state.stack.changeRoot(TestRootsAccess.HOME);
        mEnv.state.stack.push(TestEnv.FOLDER_1);
        mEnv.state.acceptMimes = new String[] { "image/*" };

        mActivity.finishedHandler.assertNotCalled();

        mHandler.finishPicking(TestEnv.FILE_JPG.derivedUri, TestEnv.FILE_GIF.derivedUri);

        mEnv.beforeAsserts();

        assertLastAccessedStackUpdated();

        assertEquals(Activity.RESULT_OK, (long) mActivity.setResult.getLastValue().first);
        final Intent result = mActivity.setResult.getLastValue().second;
        assertPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, false);
        assertPermission(result, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, false);
        assertPermission(result, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, false);
        assertContent(result, TestEnv.FILE_JPG.derivedUri, TestEnv.FILE_GIF.derivedUri);

        mActivity.finishedHandler.assertCalled();
    }

    @Test
    public void testFinishPicking_SetsCorrectResultAndFinishes_ActionOpen() throws Exception {
        mEnv.state.action = State.ACTION_OPEN;
        mEnv.state.stack.changeRoot(TestRootsAccess.HOME);
        mEnv.state.stack.push(TestEnv.FOLDER_1);

        mActivity.finishedHandler.assertNotCalled();

        mHandler.finishPicking(TestEnv.FILE_JPG.derivedUri);

        mEnv.beforeAsserts();

        assertLastAccessedStackUpdated();

        assertEquals(Activity.RESULT_OK, (long) mActivity.setResult.getLastValue().first);
        final Intent result = mActivity.setResult.getLastValue().second;
        assertPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, false);
        assertContent(result, TestEnv.FILE_JPG.derivedUri);

        mActivity.finishedHandler.assertCalled();
    }

    @Test
    public void testFinishPicking_SetsCorrectResultAndFinishes_ActionOpen_MultipleSelection()
            throws Exception {
        mEnv.state.action = State.ACTION_OPEN;
        mEnv.state.stack.changeRoot(TestRootsAccess.HOME);
        mEnv.state.stack.push(TestEnv.FOLDER_1);
        mEnv.state.acceptMimes = new String[] { "image/*" };

        mActivity.finishedHandler.assertNotCalled();

        mHandler.finishPicking(TestEnv.FILE_JPG.derivedUri, TestEnv.FILE_GIF.derivedUri);

        mEnv.beforeAsserts();

        assertLastAccessedStackUpdated();

        assertEquals(Activity.RESULT_OK, (long) mActivity.setResult.getLastValue().first);
        final Intent result = mActivity.setResult.getLastValue().second;
        assertPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, false);
        assertContent(result, TestEnv.FILE_JPG.derivedUri, TestEnv.FILE_GIF.derivedUri);

        mActivity.finishedHandler.assertCalled();
    }

    @Test
    public void testFinishPicking_SetsCorrectResultAndFinishes_ActionCreate() throws Exception {
        mEnv.state.action = State.ACTION_CREATE;
        mEnv.state.stack.changeRoot(TestRootsAccess.HOME);
        mEnv.state.stack.push(TestEnv.FOLDER_1);

        mActivity.finishedHandler.assertNotCalled();

        mHandler.finishPicking(TestEnv.FILE_JPG.derivedUri);

        mEnv.beforeAsserts();

        assertLastAccessedStackUpdated();

        assertEquals(Activity.RESULT_OK, (long) mActivity.setResult.getLastValue().first);
        final Intent result = mActivity.setResult.getLastValue().second;
        assertPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, true);
        assertPermission(result, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, false);
        assertContent(result, TestEnv.FILE_JPG.derivedUri);

        mActivity.finishedHandler.assertCalled();
    }

    private void testInitLocationDefaultToRecentsOnAction(@ActionType int action)
            throws Exception {
        mEnv.state.action = action;

        mActivity.refreshCurrentRootAndDirectory.assertNotCalled();

        mHandler.initLocation(mActivity.getIntent());

        mEnv.beforeAsserts();
        assertEquals(TestRootsAccess.RECENTS, mEnv.state.stack.getRoot());
        mActivity.refreshCurrentRootAndDirectory.assertCalled();
    }

    private void assertRootPicked(Uri expectedUri) throws Exception {
        mEnv.beforeAsserts();

        mActivity.rootPicked.assertCalled();
        RootInfo root = mActivity.rootPicked.getLastValue();
        assertNotNull(root);
        assertEquals(expectedUri, root.getUri());
    }

    private void assertLastAccessedStackUpdated() {
        assertEquals(
                mEnv.state.stack, mLastAccessed.getLastAccessed(mActivity, mEnv.roots, mEnv.state));
    }

    private void assertPermission(Intent intent, int permission, boolean granted) {
        int flags = intent.getFlags();

        if (granted) {
            assertEquals(permission, flags & permission);
        } else {
            assertEquals(0, flags & permission);
        }
    }

    private void assertContent(Intent intent, Uri... contents) {
        if (contents.length == 1) {
            assertEquals(contents[0], intent.getData());
        } else {
            ClipData clipData = intent.getClipData();

            assertNotNull(clipData);
            for (int i = 0; i < mEnv.state.acceptMimes.length; ++i) {
                assertEquals(mEnv.state.acceptMimes[i], clipData.getDescription().getMimeType(i));
            }
            for (int i = 0; i < contents.length; ++i) {
                assertEquals(contents[i], clipData.getItemAt(i).getUri());
            }
        }
    }
}
