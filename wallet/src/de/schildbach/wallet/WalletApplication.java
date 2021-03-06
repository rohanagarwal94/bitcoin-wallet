/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.LinuxSecureRandom;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Protos;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.subgraph.orchid.encoders.Hex;

import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.UsbService;
import de.schildbach.wallet.util.Bluetooth;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet_test.BuildConfig;
import de.schildbach.wallet_test.R;

import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.widget.Toast;

/**
 * @author Andreas Schildbach
 */
public class WalletApplication extends Application {
    private ActivityManager activityManager;

    private File walletFile;
    private Wallet wallet;
    private UsbService usbService;
    private static WalletApplication myApplication = null;

    // Getter to access Singleton instance
    public static WalletApplication getInstance() {
        return myApplication;
    }

    public static final String ACTION_WALLET_REFERENCE_CHANGED = WalletApplication.class.getPackage().getName()
            + ".wallet_reference_changed";

    public static final int VERSION_CODE_SHOW_BACKUP_REMINDER = 205;

    public static final long TIME_CREATE_APPLICATION = System.currentTimeMillis();

    private static final Logger log = LoggerFactory.getLogger(WalletApplication.class);


    private final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder arg1) {
            usbService = ((UsbService.UsbBinder) arg1).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            usbService = null;
        }
    };

    @Override
    public void onCreate() {
        new LinuxSecureRandom(); // init proper random number generator

        Logging.init(getFilesDir());

        myApplication = this;

        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().permitDiskReads()
                .permitDiskWrites().penaltyLog().build());

        Threading.throwOnLockCycles();
        org.bitcoinj.core.Context.enableStrictMode();
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

        log.info("=== starting app using configuration: {}, {}", Constants.TEST ? "test" : "prod",
                Constants.NETWORK_PARAMETERS.getId());

        super.onCreate();

        CrashReporter.init(getCacheDir());

        final PackageInfo packageInfo = packageInfo();

        Threading.uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(final Thread thread, final Throwable throwable) {
                log.info("bitcoinj uncaught exception", throwable);
                CrashReporter.saveBackgroundTrace(throwable, packageInfo);
            }
        };

        initMnemonicCode();

        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

        walletFile = getFileStreamPath(Constants.Files.WALLET_FILENAME_PROTOBUF);

        loadWalletFromProtobuf();

        final Configuration config = getConfiguration();
        if (config.versionCodeCrossed(packageInfo.versionCode, VERSION_CODE_SHOW_BACKUP_REMINDER)
                && !wallet.getImportedKeys().isEmpty()) {
            log.info("showing backup reminder once, because of imported keys being present");
            config.armBackupReminder();
        }
        config.updateLastVersionCode(packageInfo.versionCode);
        final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null)
            config.updateLastBluetoothAddress(Bluetooth.getAddress(bluetoothAdapter));

        afterLoadWallet();

        cleanupFiles();

        initNotificationManager();
    }

    private void afterLoadWallet() {
        wallet.autosaveToFile(walletFile, Constants.Files.WALLET_AUTOSAVE_DELAY_MS, TimeUnit.MILLISECONDS, null);

        // clean up spam
        wallet.cleanup();

        // make sure there is at least one recent backup
        if (!getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF).exists())
            backupWallet();
    }

    private static final String BIP39_WORDLIST_FILENAME = "bip39-wordlist.txt";

    private void initMnemonicCode() {
        try {
            final Stopwatch watch = Stopwatch.createStarted();
            MnemonicCode.INSTANCE = new MnemonicCode(getAssets().open(BIP39_WORDLIST_FILENAME), null);
            watch.stop();
            log.info("BIP39 wordlist loaded from: '{}', took {}", BIP39_WORDLIST_FILENAME, watch);
        } catch (final IOException x) {
            throw new Error(x);
        }
    }

    private Configuration config;

    public synchronized Configuration getConfiguration() {
        if (config == null)
            config = new Configuration(PreferenceManager.getDefaultSharedPreferences(this), getResources());
        return config;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public UsbService getUsbService() {
        return usbService;
    }

    public ServiceConnection getUsbConnection() {
        return usbConnection;
    }

    private void loadWalletFromProtobuf() {
        if (walletFile.exists()) {
            try (final FileInputStream walletStream = new FileInputStream(walletFile)) {
                final Stopwatch watch = Stopwatch.createStarted();
                wallet = new WalletProtobufSerializer().readWallet(walletStream);
                watch.stop();

                if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS))
                    throw new UnreadableWalletException("bad wallet network parameters: " + wallet.getParams().getId());

                log.info("wallet loaded from: '{}', took {}", walletFile, watch);
            } catch (final IOException | UnreadableWalletException x) {
                log.error("problem loading wallet", x);

                Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

                wallet = restoreWalletFromBackup();
            }

            if (!wallet.isConsistent()) {
                Toast.makeText(this, "inconsistent wallet: " + walletFile, Toast.LENGTH_LONG).show();

                wallet = restoreWalletFromBackup();
            }

            if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS))
                throw new Error("bad wallet network parameters: " + wallet.getParams().getId());

        } else {
            final Stopwatch watch = Stopwatch.createStarted();
            String xPub = "tpubD9cB4Cke48quFjAK4eqQqXgYrKwHDgHuNe3bMio4jj4ACJeQ1D74A6HeHuRbUfnffPgEjivpJG2Cx8yLUunbKPfytcfNot4yvdz7zK9F7mo";
            wallet = Wallet.fromWatchingKeyB58(Constants.NETWORK_PARAMETERS, xPub, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
//            wallet.importKey(ECKey.fromPublicOnly(Hex.decode("048144f0617ca9f46faf22c3a2aabe2b5a70b41b33d943eedeab45fb0ea39db03bc29a10850dcc0a8a9459a117c39cf773550f3ed72a4fcfb20b6755086c0419d6")));
//            wallet.addWatchedAddress(Address.fromBase58(Constants.NETWORK_PARAMETERS, "msMSr52jyP8HV3Kx9uWRPFHHU1KPPJpYX9"));
            saveWallet();
            backupWallet();
            watch.stop();
            log.info("fresh wallet created, took {}", watch);

            // Print the very first derived address from provided public key
            System.out.println("Receiving Address : " + wallet.currentReceiveAddress());
//
            for(int i=0;i<5;i++) {
                // Run this statement in a loop, where i is the loop variable.
                System.out.println(i + " : " + wallet.freshReceiveAddress());
//                Toast.makeText(this,i, Toast.LENGTH_SHORT).show();
            }

//            Peergroup.broadcasttransaction(); // For broadcasting

            config.armBackupReminder();
        }
    }

    private Wallet restoreWalletFromBackup() {
        try (final InputStream is = openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF)) {
            final Wallet wallet = new WalletProtobufSerializer().readWallet(is, true, null);

            if (!wallet.isConsistent())
                throw new Error("inconsistent backup");

            BlockchainService.resetBlockchain(this);

            Toast.makeText(this, R.string.toast_wallet_reset, Toast.LENGTH_LONG).show();

            log.info("wallet restored from backup: '" + Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + "'");

            return wallet;
        } catch (final IOException | UnreadableWalletException x) {
            throw new Error("cannot read backup", x);
        }
    }

    public void saveWallet() {
        try {
            protobufSerializeWallet(wallet);
        } catch (final IOException x) {
            throw new RuntimeException(x);
        }
    }

    private void protobufSerializeWallet(final Wallet wallet) throws IOException {
        final Stopwatch watch = Stopwatch.createStarted();
        wallet.saveToFile(walletFile);
        watch.stop();

        log.info("wallet saved to: '{}', took {}", walletFile, watch);
    }

    public void backupWallet() {
        final Stopwatch watch = Stopwatch.createStarted();
        final Protos.Wallet.Builder builder = new WalletProtobufSerializer().walletToProto(wallet).toBuilder();

        // strip redundant
        builder.clearTransaction();
        builder.clearLastSeenBlockHash();
        builder.setLastSeenBlockHeight(-1);
        builder.clearLastSeenBlockTimeSecs();
        final Protos.Wallet walletProto = builder.build();

        try (final OutputStream os = openFileOutput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, Context.MODE_PRIVATE)) {
            walletProto.writeTo(os);
            watch.stop();
            log.info("wallet backed up to: '{}', took {}", Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, watch);
        } catch (final IOException x) {
            log.error("problem writing wallet backup", x);
        }
    }

    private void cleanupFiles() {
        for (final String filename : fileList()) {
            if (filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_BASE58)
                    || filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + '.')
                    || filename.endsWith(".tmp")) {
                final File file = new File(getFilesDir(), filename);
                log.info("removing obsolete file: '{}'", file);
                file.delete();
            }
        }
    }

    private void initNotificationManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Stopwatch watch = Stopwatch.createStarted();
            final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            final NotificationChannel received = new NotificationChannel(Constants.NOTIFICATION_CHANNEL_ID_RECEIVED,
                    getString(R.string.notification_channel_received_name), NotificationManager.IMPORTANCE_DEFAULT);
            received.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.coins_received),
                    new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build());
            nm.createNotificationChannel(received);

            final NotificationChannel ongoing = new NotificationChannel(Constants.NOTIFICATION_CHANNEL_ID_ONGOING,
                    getString(R.string.notification_channel_ongoing_name), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ongoing);

            final NotificationChannel important = new NotificationChannel(Constants.NOTIFICATION_CHANNEL_ID_IMPORTANT,
                    getString(R.string.notification_channel_important_name), NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(important);

            log.info("created notification channels, took {}", watch);
        }
    }

    public void replaceWallet(final Wallet newWallet) {
        BlockchainService.resetBlockchain(this);
        wallet.shutdownAutosaveAndWait();

        wallet = newWallet;
        config.maybeIncrementBestChainHeightEver(newWallet.getLastBlockSeenHeight());
        afterLoadWallet();

        final Intent broadcast = new Intent(ACTION_WALLET_REFERENCE_CHANGED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    public void processDirectTransaction(final Transaction tx) throws VerificationException {
        if (wallet.isTransactionRelevant(tx)) {
            wallet.receivePending(tx, null);
            BlockchainService.broadcastTransaction(this, tx);
        }
    }

    private PackageInfo packageInfo;

    public synchronized PackageInfo packageInfo() {
        // replace by BuildConfig.VERSION_* as soon as it's possible
        if (packageInfo == null) {
            try {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            } catch (final NameNotFoundException x) {
                throw new RuntimeException(x);
            }
        }
        return packageInfo;
    }

    public final String applicationPackageFlavor() {
        final String packageName = getPackageName();
        final int index = packageName.lastIndexOf('_');

        if (index != -1)
            return packageName.substring(index + 1);
        else
            return null;
    }

    public static String httpUserAgent(final String versionName) {
        final VersionMessage versionMessage = new VersionMessage(Constants.NETWORK_PARAMETERS, 0);
        versionMessage.appendToSubVer(Constants.USER_AGENT, versionName, null);
        return versionMessage.subVer;
    }

    public String httpUserAgent() {
        return httpUserAgent(packageInfo().versionName);
    }

    public int maxConnectedPeers() {
        return activityManager.isLowRamDevice() ? 4 : 6;
    }

    public int scryptIterationsTarget() {
        return activityManager.isLowRamDevice() ? Constants.SCRYPT_ITERATIONS_TARGET_LOWRAM
                : Constants.SCRYPT_ITERATIONS_TARGET;
    }

    public static String versionLine(final PackageInfo packageInfo) {
        return ImmutableList.copyOf(Splitter.on('.').splitToList(packageInfo.packageName)).reverse().get(0) + ' '
                + packageInfo.versionName + (BuildConfig.DEBUG ? " (debuggable)" : "");
    }
}
