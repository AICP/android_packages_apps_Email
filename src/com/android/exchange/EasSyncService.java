/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

package com.android.exchange;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;

import java.util.ArrayList;

import javax.net.ssl.HttpsURLConnection;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.impl.client.DefaultHttpClient;

import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.MessagingException;
import com.android.exchange.EmailContent.Account;
import com.android.exchange.EmailContent.Attachment;
import com.android.exchange.EmailContent.AttachmentColumns;
import com.android.exchange.EmailContent.HostAuth;
import com.android.exchange.EmailContent.Mailbox;
import com.android.exchange.EmailContent.MailboxColumns;
import com.android.exchange.adapter.EasContactsSyncAdapter;
import com.android.exchange.adapter.EasEmailSyncAdapter;
import com.android.exchange.adapter.EasFolderSyncParser;
import com.android.exchange.adapter.EasPingParser;
import com.android.exchange.adapter.EasSerializer;
import com.android.exchange.adapter.EasSyncAdapter;
import com.android.exchange.adapter.EasParser.EasParserException;
import com.android.exchange.utility.Base64;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

public class EasSyncService extends InteractiveSyncService {

    private static final String WINDOW_SIZE = "10";
    private static final String WHERE_ACCOUNT_KEY_AND_SERVER_ID =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SERVER_ID + "=?";
    private static final String WHERE_SYNC_FREQUENCY_PING =
        Mailbox.SYNC_FREQUENCY + '=' + Account.CHECK_INTERVAL_PING;
    private static final String SYNC_FREQUENCY_PING =
        MailboxColumns.SYNC_FREQUENCY + '=' + Account.CHECK_INTERVAL_PING;
    
    // Reasonable default
    String mProtocolVersion = "2.5";
    static String mDeviceId = null;
    static String mDeviceType = "Android";
    EasSyncAdapter mTarget;
    String mAuthString = null;
    String mCmdString = null;
    String mVersions;
    public String mHostAddress;
    public String mUserName;
    public String mPassword;
    String mDomain = null;
    boolean mSentCommands;
    boolean mIsIdle = false;
    boolean mSsl = true;
    public Context mContext;
    public ContentResolver mContentResolver;
    String[] mBindArguments = new String[2];
    InputStream mPendingPartInputStream = null;
    private boolean mStop = false;
    private Object mWaitTarget = new Object();

    public EasSyncService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
        mContext = _context;
        mContentResolver = _context.getContentResolver();
        HostAuth ha = HostAuth.restoreHostAuthWithId(_context, mAccount.mHostAuthKeyRecv);
        mSsl = (ha.mFlags & HostAuth.FLAG_SSL) != 0;
    }

    private EasSyncService(String prefix) {
        super(prefix);
    }

    public EasSyncService() {
        this("EAS Validation");
    }

    @Override
    public void ping() {
        userLog("We've been pinged!");
        synchronized (mWaitTarget) {
            mWaitTarget.notify();
        }
    }

    @Override
    public void stop() {
        mStop = true;
    }

    public int getSyncStatus() {
        return 0;
    }

    /* (non-Javadoc)
     * @see com.android.exchange.SyncService#validateAccount(java.lang.String, java.lang.String, java.lang.String, int, boolean, android.content.Context)
     */
    public void validateAccount(String hostAddress, String userName, String password, int port,
            boolean ssl, Context context) throws MessagingException {
        try {
            if (Eas.USER_DEBUG) {
                userLog("Testing EAS: " + hostAddress + ", " + userName + ", ssl = " + ssl);
            }
            EasSerializer s = new EasSerializer();
            s.start("FolderSync").start("FolderSyncKey").text("0").end("FolderSyncKey")
                .end("FolderSync").end();
            EasSyncService svc = new EasSyncService("%TestAccount%");
            svc.mHostAddress = hostAddress;
            svc.mUserName = userName;
            svc.mPassword = password;
            svc.mSsl = ssl;
            HttpURLConnection uc = svc.sendEASPostCommand("FolderSync", s.toString());
            int code = uc.getResponseCode();
            userLog("Validation response code: " + code);
            if (code == HttpURLConnection.HTTP_OK) {
                // No exception means successful validation
                userLog("Validation successful");
                return;
            }
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    code == HttpURLConnection.HTTP_FORBIDDEN) {
                userLog("Authentication failed");
                throw new AuthenticationFailedException("Validation failed");
            } else {
                // TODO Need to catch other kinds of errors (e.g. policy) For now, report the code.
                userLog("Validation failed, reporting I/O error: " + code);
                throw new MessagingException(MessagingException.IOERROR);
            }
        } catch (IOException e) {
            userLog("IOException caught, reporting I/O error: " + e.getMessage());
            throw new MessagingException(MessagingException.IOERROR);
        }

    }


    @Override
    public void loadAttachment(Attachment att, IEmailServiceCallback cb) {
        // TODO Auto-generated method stub
    }

    @Override
    public void reloadFolderList() {
        // TODO Auto-generated method stub
    }

    @Override
    public void startSync() {
        // TODO Auto-generated method stub
    }

    @Override
    public void stopSync() {
        // TODO Auto-generated method stub
    }

   protected HttpURLConnection sendEASPostCommand(String cmd, String data) throws IOException {
        HttpURLConnection uc = setupEASCommand("POST", cmd);
        if (uc != null) {
            uc.setRequestProperty("Content-Length", Integer.toString(data.length() + 2));
            OutputStreamWriter w = new OutputStreamWriter(uc.getOutputStream(), "UTF-8");
            w.write(data);
            w.write("\r\n");
            w.flush();
            w.close();
        }
        return uc;
    }

    static private final int CHUNK_SIZE = 16 * 1024;

    protected void getAttachment(PartRequest req) throws IOException {
        DefaultHttpClient client = new DefaultHttpClient();
        String us = makeUriString("GetAttachment", "&AttachmentName=" + req.att.mLocation);
        HttpPost method = new HttpPost(URI.create(us));
        method.setHeader("Authorization", mAuthString);

        HttpResponse res = client.execute(method);
        int status = res.getStatusLine().getStatusCode();
        if (status == HttpURLConnection.HTTP_OK) {
            HttpEntity e = res.getEntity();
            int len = (int)e.getContentLength();
            String type = e.getContentType().getValue();
            if (Eas.TEST_DEBUG) {
                Log.v(TAG, "Attachment code: " + status + ", Length: " + len + ", Type: " + type);
            }
            InputStream is = res.getEntity().getContent();
            // TODO Use the request data, when it's defined.  For now, stubbed out
            File f = null; // Attachment.openAttachmentFile(req);
            if (f != null) {
                FileOutputStream os = new FileOutputStream(f);
                if (len > 0) {
                    try {
                        mPendingPartRequest = req;
                        mPendingPartInputStream = is;
                        byte[] bytes = new byte[CHUNK_SIZE];
                        int length = len;
                        while (len > 0) {
                            int n = (len > CHUNK_SIZE ? CHUNK_SIZE : len);
                            int read = is.read(bytes, 0, n);
                            os.write(bytes, 0, read);
                            len -= read;
                            if (req.handler != null) {
                                long pct = ((length - len) * 100 / length);
                                req.handler.sendEmptyMessage((int)pct);
                            }
                        }
                    } finally {
                        mPendingPartRequest = null;
                        mPendingPartInputStream = null;
                    }
                }
                os.flush();
                os.close();

                ContentValues cv = new ContentValues();
                cv.put(AttachmentColumns.CONTENT_URI, f.getAbsolutePath());
                cv.put(AttachmentColumns.MIME_TYPE, type);
                req.att.update(mContext, cv);
                // TODO Inform UI that we're done
            }
        }
    }

    private HttpURLConnection setupEASCommand(String method, String cmd) throws IOException {
        return setupEASCommand(method, cmd, null);
    }

    private String makeUriString(String cmd, String extra) {
         // Cache the authentication string and the command string
        if (mDeviceId == null)
            mDeviceId = "droidfu";
        String safeUserName = URLEncoder.encode(mUserName);
        if (mAuthString == null) {
            String cs = mUserName + ':' + mPassword;
            mAuthString = "Basic " + Base64.encodeBytes(cs.getBytes());
            mCmdString = "&User=" + safeUserName + "&DeviceId=" + mDeviceId + "&DeviceType="
                    + mDeviceType;
        }

        String us = (mSsl ? "https" : "http") + "://" + mHostAddress +
            "/Microsoft-Server-ActiveSync";
        if (cmd != null) {
            us += "?Cmd=" + cmd + mCmdString;
        }
        if (extra != null) {
            us += extra;
        }
        return us;
    }

    private HttpURLConnection setupEASCommand(String method, String cmd, String extra) 
            throws IOException {
        try {
            String us = makeUriString(cmd, extra);
            URL u = new URL(us);
            HttpURLConnection uc = (HttpURLConnection)u.openConnection();
            HttpURLConnection.setFollowRedirects(true);
 
            if (mSsl) {
                ((HttpsURLConnection)uc).setHostnameVerifier(new AllowAllHostnameVerifier());
            }

            uc.setConnectTimeout(10 * SECS);
            uc.setReadTimeout(20 * MINS);
            if (method.equals("POST")) {
                uc.setDoOutput(true);
            }
            uc.setRequestMethod(method);
            uc.setRequestProperty("Authorization", mAuthString);

            if (extra == null) {
                if (cmd != null && cmd.startsWith("SendMail&")) {
                    uc.setRequestProperty("Content-Type", "message/rfc822");
                } else {
                    uc.setRequestProperty("Content-Type", "application/vnd.ms-sync.wbxml");
                }
                uc.setRequestProperty("MS-ASProtocolVersion", mProtocolVersion);
                uc.setRequestProperty("Connection", "keep-alive");
                uc.setRequestProperty("User-Agent", mDeviceType + '/' + Eas.VERSION);
            } else {
                uc.setRequestProperty("Content-Length", "0");
            }

            return uc;
        } catch (MalformedURLException e) {
            // TODO See if there is a better exception to throw here and below
            throw new IOException();
        } catch (ProtocolException e) {
            throw new IOException();
        }
    }

    String getTargetCollectionClassFromCursor(Cursor c) {
        int type = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
        if (type == Mailbox.TYPE_CONTACTS) {
            return "Contacts";
        } else if (type == Mailbox.TYPE_CALENDAR) {
            return "Calendar";
        } else {
            return "Email";
        }
    }

    /**
     * Performs FolderSync
     *
     * @throws IOException
     * @throws EasParserException
     */
    public void runMain() throws IOException, EasParserException {
        try {
            if (mAccount.mSyncKey == null) {
                mAccount.mSyncKey = "0";
                userLog("Account syncKey RESET");
                mAccount.saveOrUpdate(mContext);
            }

            // When we first start up, change all ping mailboxes to push.
            ContentValues cv = new ContentValues();
            cv.put(Mailbox.SYNC_FREQUENCY, Account.CHECK_INTERVAL_PUSH);
            if (mContentResolver.update(Mailbox.CONTENT_URI, cv,
                    WHERE_SYNC_FREQUENCY_PING, null) > 0) {
                SyncManager.kick();
            }

            userLog("Account syncKey: " + mAccount.mSyncKey);
            HttpURLConnection uc = setupEASCommand("OPTIONS", null);
            if (uc != null) {
                int code = uc.getResponseCode();
                userLog("OPTIONS response: " + code);
                if (code == HttpURLConnection.HTTP_OK) {
                    mVersions = uc.getHeaderField("ms-asprotocolversions");
                    if (mVersions != null) {
                        if (mVersions.contains("12.0")) {
                            mProtocolVersion = "12.0";
                        }
                        // TODO We only do 2.5 at the moment; add 'else' above when fixed
                        mProtocolVersion = "2.5";
                        userLog(mVersions);
                    } else {
                        throw new IOException();
                    }

                    while (!mStop) {
                        EasSerializer s = new EasSerializer();
                        s.start("FolderSync").start("FolderSyncKey").text(mAccount.mSyncKey).end(
                                "FolderSyncKey").end("FolderSync").end();
                        uc = sendEASPostCommand("FolderSync", s.toString());
                        code = uc.getResponseCode();
                        if (code == HttpURLConnection.HTTP_OK) {
                            String encoding = uc.getHeaderField("Transfer-Encoding");
                            if (encoding == null) {
                                int len = uc.getHeaderFieldInt("Content-Length", 0);
                                if (len > 0) {
                                    InputStream is = uc.getInputStream();
                                    // Returns true if we need to sync again
                                    if (new EasFolderSyncParser(is, this).parse()) {
                                        continue;
                                    }
                                }
                            } else if (encoding.equalsIgnoreCase("chunked")) {
                                // TODO We don't handle this yet
                            }
                        } else {
                            userLog("FolderSync response error: " + code);
                        }

                        // Wait for push notifications.
                        try {
                            runPingLoop();
                        } catch (StaleFolderListException e) {
                            // We break out if we get told about a stale folder list
                            userLog("Ping interrupted; folder list requires sync...");
                        }
                    }
                 }
            }
        } catch (MalformedURLException e) {
            throw new IOException();
        }
    }

    void runPingLoop() throws IOException, StaleFolderListException {
        // Do push for all sync services here
        long endTime = System.currentTimeMillis() + (30*MINS);

        while (System.currentTimeMillis() < endTime) {
            // Count of pushable mailboxes
            int pushCount = 0;
            // Count of mailboxes that can be pushed right now
            int canPushCount = 0;
            EasSerializer s = new EasSerializer();
            HttpURLConnection uc;
            int code;
            Cursor c = mContentResolver.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                    MailboxColumns.ACCOUNT_KEY + '=' + mAccount.mId + " and " + SYNC_FREQUENCY_PING,
                    null, null);

            try {
                // Loop through our pushed boxes seeing what is available to push
                while (c.moveToNext()) {
                    pushCount++;
                    // Two requirements for push:
                    // 1) SyncManager tells us the mailbox is syncable (not running, not stopped)
                    // 2) The syncKey isn't "0" (i.e. it's synced at least once)
                    if (SyncManager.canSync(c.getLong(Mailbox.CONTENT_ID_COLUMN))) {
                        String syncKey = c.getString(Mailbox.CONTENT_SYNC_KEY_COLUMN);
                        if (syncKey == null || syncKey.equals("0")) {
                            continue;
                        }
                        if (canPushCount++ == 0) {
                            // Initialize the Ping command
                            s.start("Ping").data("HeartbeatInterval", "900").start("PingFolders");
                        }
                        // When we're ready for Calendar/Contacts, we will check folder type
                        // TODO Save Calendar and Contacts!! Mark as not visible!
                        String folderClass = getTargetCollectionClassFromCursor(c);
                        s.start("PingFolder")
                            .data("PingId", c.getString(Mailbox.CONTENT_SERVER_ID_COLUMN))
                            .data("PingClass", folderClass)
                            .end("PingFolder");
                    }
                }
            } finally {
                c.close();
            }

            if (canPushCount > 0) {
                // If we have some number that are ready for push, send Ping to the server
                s.end("PingFolders").end("Ping").end();
                uc = sendEASPostCommand("Ping", s.toString());
                userLog("Sending ping, timeout: " + uc.getReadTimeout() / 1000 + "s");
                code = uc.getResponseCode();
                userLog("Ping response: " + code);
                if (code == HttpURLConnection.HTTP_OK) {
                    String encoding = uc.getHeaderField("Transfer-Encoding");
                    if (encoding == null) {
                        int len = uc.getHeaderFieldInt("Content-Length", 0);
                        if (len > 0) {
                            parsePingResult(uc, mContentResolver);
                        } else {
                            // This implies a connection issue that we can't handle
                            throw new IOException();
                        }
                    } else {
                        // It shouldn't be possible for EAS server to send chunked data here
                        throw new IOException();
                    }
                } else if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        code == HttpURLConnection.HTTP_FORBIDDEN) {
                    mExitStatus = AbstractSyncService.EXIT_LOGIN_FAILURE;
                    userLog("Authorization error during Ping: " + code);
                    throw new IOException();
                }
            } else if (pushCount > 0) {
                // If we want to Ping, but can't just yet, wait 10 seconds and try again
                sleep(10*SECS);
            } else {
                // We've got nothing to do, so let's hang out for a while
                sleep(10*MINS);
            }
        }
    }

    void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Doesn't matter whether we stop early; it's the thought that counts
        }
    }

    void parsePingResult(HttpURLConnection uc, ContentResolver cr)
        throws IOException, StaleFolderListException {
        EasPingParser pp = new EasPingParser(uc.getInputStream(), this);
        if (pp.parse()) {
            // True indicates some mailboxes need syncing...
            // syncList has the serverId's of the mailboxes...
            mBindArguments[0] = Long.toString(mAccount.mId);
            ArrayList<String> syncList = pp.getSyncList();
            for (String serverId: syncList) {
                 mBindArguments[1] = serverId;
                Cursor c = cr.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                        WHERE_ACCOUNT_KEY_AND_SERVER_ID, mBindArguments, null);
                try {
                    if (c.moveToFirst()) {
                        SyncManager.startManualSync(c.getLong(Mailbox.CONTENT_ID_COLUMN));
                    }
                } finally {
                    c.close();
                }
            }
        }
    }

    ByteArrayInputStream readResponse(HttpURLConnection uc) throws IOException {
        String encoding = uc.getHeaderField("Transfer-Encoding");
        if (encoding == null) {
            int len = uc.getHeaderFieldInt("Content-Length", 0);
            if (len > 0) {
                InputStream in = uc.getInputStream();
                byte[] bytes = new byte[len];
                int remain = len;
                int offs = 0;
                while (remain > 0) {
                    int read = in.read(bytes, offs, remain);
                    remain -= read;
                    offs += read;
                }
                return new ByteArrayInputStream(bytes);
            }
        } else if (encoding.equalsIgnoreCase("chunked")) {
            // TODO We don't handle this yet
            return null;
        }
        return null;
    }

    String readResponseString(HttpURLConnection uc) throws IOException {
        String encoding = uc.getHeaderField("Transfer-Encoding");
        if (encoding == null) {
            int len = uc.getHeaderFieldInt("Content-Length", 0);
            if (len > 0) {
                InputStream in = uc.getInputStream();
                byte[] bytes = new byte[len];
                int remain = len;
                int offs = 0;
                while (remain > 0) {
                    int read = in.read(bytes, offs, remain);
                    remain -= read;
                    offs += read;
                }
                return new String(bytes);
            }
        } else if (encoding.equalsIgnoreCase("chunked")) {
            // TODO We don't handle this yet
            return null;
        }
        return null;
    }

    /**
     * EAS requires a unique device id, so that sync is possible from a variety of different
     * devices (e.g. the syncKey is specific to a device)  If we're on an emulator or some other
     * device that doesn't provide one, we can create it as droid<n> where <n> is system time.
     * This would work on a real device as well, but it would be better to use the "real" id if
     * it's available
     */
    private String getSimulatedDeviceId() {
        try {
            File f = mContext.getFileStreamPath("deviceName");
            BufferedReader rdr = null;
            String id;
            if (f.exists() && f.canRead()) {
                rdr = new BufferedReader(new FileReader(f));
                id = rdr.readLine();
                rdr.close();
                return id;
            } else if (f.createNewFile()) {
                BufferedWriter w = new BufferedWriter(new FileWriter(f));
                id = "droid" + System.currentTimeMillis();
                w.write(id);
                w.close();
            }
        } catch (FileNotFoundException e) {
            // We'll just use the default below
        } catch (IOException e) {
            // We'll just use the default below
        }
        return "droid0";
    }

    /**
     * Common code to sync E+PIM data
     *
     * @param target, an EasMailbox, EasContacts, or EasCalendar object
     */
    public void sync(EasSyncAdapter target) throws IOException {
        mTarget = target;
        Mailbox mailbox = target.mMailbox;

        boolean moreAvailable = true;
        while (!mStop && moreAvailable) {
            runAwake();
            waitForConnectivity();

            EasSerializer s = new EasSerializer();
            if (mailbox.mSyncKey == null) {
                userLog("Mailbox syncKey RESET");
                mailbox.mSyncKey = "0";
                mailbox.mSyncFrequency = Account.CHECK_INTERVAL_PUSH;
            }
            String className = target.getCollectionName();
            userLog("Sending " + className + " syncKey: " + mailbox.mSyncKey);
            s.start("Sync")
                .start("Collections")
                .start("Collection")
                .data("Class", className)
                .data("SyncKey", mailbox.mSyncKey)
                .data("CollectionId", mailbox.mServerId)
                .tag("DeletesAsMoves");

            // EAS doesn't like GetChanges if the syncKey is "0"; not documented
            if (!mailbox.mSyncKey.equals("0")) {
                s.tag("GetChanges");
            }
            s.data("WindowSize", WINDOW_SIZE);
            boolean options = false;
            if (!className.equals("Contacts")) {
                options = true;
                // Set the lookback appropriately (EAS calls this a "filter")
                String filter = Eas.FILTER_1_WEEK;
                switch (mAccount.mSyncLookback) {
                    case com.android.email.Account.SYNC_WINDOW_1_DAY: {
                        filter = Eas.FILTER_1_DAY;
                        break;
                    }
                    case com.android.email.Account.SYNC_WINDOW_3_DAYS: {
                        filter = Eas.FILTER_3_DAYS;
                        break;
                    }
                    case com.android.email.Account.SYNC_WINDOW_1_WEEK: {
                        filter = Eas.FILTER_1_WEEK;
                        break;
                    }
                    case com.android.email.Account.SYNC_WINDOW_2_WEEKS: {
                        filter = Eas.FILTER_2_WEEKS;
                        break;
                    }
                    case com.android.email.Account.SYNC_WINDOW_1_MONTH: {
                        filter = Eas.FILTER_1_MONTH;
                        break;
                    }
                    case com.android.email.Account.SYNC_WINDOW_ALL: {
                        filter = Eas.FILTER_ALL;
                        break;
                    }
                }
                s.start("Options")
                    .data("FilterType", filter);
            }
            if (mProtocolVersion.equals("12.0")) {
                if (!options) {
                    options = true;
                    s.start("Options");
                    s.start("BodyPreference")
                        // Plain text to start
                        .data("BodyPreferenceType", Eas.BODY_PREFERENCE_TEXT)
                        .data("BodyPreferenceTruncationSize", Eas.DEFAULT_BODY_TRUNCATION_SIZE)
                        .end("BodyPreference");
                }
            }
            if (options) {
                s.end("Options");
            }

            // Send our changes up to the server
            target.sendLocalChanges(s, this);

            s.end("Collection").end("Collections").end("Sync").end();
            HttpURLConnection uc = sendEASPostCommand("Sync", s.toString());
            int code = uc.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                ByteArrayInputStream is = readResponse(uc);
                if (is != null) {
                    moreAvailable = target.parse(is, this);
                    target.cleanup(this);
                }
            } else {
                userLog("Sync response error: " + code);
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        code == HttpURLConnection.HTTP_FORBIDDEN) {
                    mExitStatus = AbstractSyncService.EXIT_LOGIN_FAILURE;
                }
                return;
            }
        }
    }

    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    public void run() {
        mThread = Thread.currentThread();
        TAG = mThread.getName();
        mDeviceId = android.provider.Settings.System.getString(mContext.getContentResolver(),
                android.provider.Settings.System.ANDROID_ID);
        // Generate a device id if we don't have one
        if (mDeviceId == null) {
            mDeviceId = getSimulatedDeviceId();
        }
        HostAuth ha = HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        mHostAddress = ha.mAddress;
        mUserName = ha.mLogin;
        mPassword = ha.mPassword;

        try {
            if (mMailbox.mServerId.equals("_main")) {
                runMain();
            } else {
                EasSyncAdapter target;
                if (mMailbox.mType == Mailbox.TYPE_CONTACTS)
                    target = new EasContactsSyncAdapter(mMailbox);
                else {
                    target = new EasEmailSyncAdapter(mMailbox);
                }
                // We loop here because someone might have put a request in while we were syncing
                // and we've missed that opportunity...
                do {
                    if (mRequestTime != 0) {
                        userLog("Looping for user request...");
                        mRequestTime = 0;
                    }
                    sync(target);
                } while (mRequestTime != 0);
            }
            mExitStatus = EXIT_DONE;
        } catch (IOException e) {
            userLog("Caught IOException");
            mExitStatus = EXIT_IO_ERROR;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            userLog(mMailbox.mDisplayName + ": sync finished");
            SyncManager.done(this);
        }
    }
}
