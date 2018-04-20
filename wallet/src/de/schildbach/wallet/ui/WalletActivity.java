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

package de.schildbach.wallet.ui;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.VersionedChecksummedBytes;
import org.bitcoinj.wallet.Wallet;

import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.api.ClientBuilder;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.model.PushTx;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.UsbService;
import de.schildbach.wallet.ui.InputParser.BinaryInputParser;
import de.schildbach.wallet.ui.InputParser.StringInputParser;
import de.schildbach.wallet.ui.backup.BackupWalletDialogFragment;
import de.schildbach.wallet.ui.backup.RestoreWalletDialogFragment;
import de.schildbach.wallet.ui.preference.PreferenceActivity;
import de.schildbach.wallet.ui.send.SendCoinsActivity;
import de.schildbach.wallet.ui.send.SendCoinsFragment;
import de.schildbach.wallet.ui.send.SweepWalletActivity;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

/**
 * @author Andreas Schildbach
 */
public final class WalletActivity extends AbstractBindServiceActivity {
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private boolean isConnect = false;
    private SendCoinsFragment.MyHandler mHandler;

    public static final String TAG = "WalletActivity";

    private UsbService usbService;
    private ServiceConnection usbConnection;

    private Handler handler = new Handler();

    private StringBuilder receivedData = new StringBuilder(128);
    private StringBuilder tempReceivedData = new StringBuilder(128);

    private static final int REQUEST_CODE_SCAN = 0;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        application = getWalletApplication();
        config = application.getConfiguration();
        wallet = application.getWallet();
        usbService = application.getUsbService();
        usbConnection = application.getUsbConnection();

        setContentView(R.layout.wallet_content);

        mHandler = new SendCoinsFragment.MyHandler(this);

        final View exchangeRatesFragment = findViewById(R.id.wallet_main_twopanes_exchange_rates);
        if (exchangeRatesFragment != null)
            exchangeRatesFragment.setVisibility(Constants.ENABLE_EXCHANGE_RATES ? View.VISIBLE : View.GONE);

        if (savedInstanceState == null) {
            final View contentView = findViewById(android.R.id.content);
            final View slideInLeftView = contentView.findViewWithTag("slide_in_left");
            if (slideInLeftView != null)
                slideInLeftView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_left));
            final View slideInRightView = contentView.findViewWithTag("slide_in_right");
            if (slideInRightView != null)
                slideInRightView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_right));
            final View slideInTopView = contentView.findViewWithTag("slide_in_top");
            if (slideInTopView != null)
                slideInTopView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_top));
            final View slideInBottomView = contentView.findViewWithTag("slide_in_bottom");
            if (slideInBottomView != null)
                slideInBottomView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom));

            checkSavedCrashTrace();
        }

        config.touchLastUsed();

        handleIntent(getIntent());

        final FragmentManager fragmentManager = getSupportFragmentManager();
        MaybeMaintenanceFragment.add(fragmentManager);
        AlertDialogsFragment.add(fragmentManager);
    }

    private void startService(Class<?> service, ServiceConnection serviceConnection) {
        if (!UsbService.SERVICE_CONNECTED) {
            Intent startService = new Intent(this, service);
            startService(startService);
        }
        Intent bindingIntent = new Intent(this, service);
        bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case UsbService.ACTION_USB_PERMISSION_GRANTED:
                    Toast.makeText(context,
                            getString(R.string.usb_permission_granted),
                            Toast.LENGTH_SHORT).show();
                    requestConnection();
                    break;
                case UsbService.ACTION_USB_PERMISSION_NOT_GRANTED:
                    Toast.makeText(context,
                            getString(R.string.usb_permission_not_granted),
                            Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_NO_USB:
                    Toast.makeText(context,
                            getString(R.string.no_usb),
                            Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_DISCONNECTED:
                    Toast.makeText(context,
                            getString(R.string.usb_disconnected),
                            Toast.LENGTH_SHORT).show();
                    stopConnection();
                    break;
                case UsbService.ACTION_USB_NOT_SUPPORTED:
                    Toast.makeText(context, getString(R.string.usb_not_supported),
                            Toast.LENGTH_SHORT).show();
                    break;
                default:
                    Log.e(TAG, "Unknown action");
                    break;
            }
        }
    };

    private void startConnection() {
        usbService.setHandler(mHandler);
        isConnect = true;
        Toast.makeText(this,
                getString(R.string.start_connection),Toast.LENGTH_SHORT).show();
    }

    private void requestConnection() {
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
        alertDialog.setMessage(getString(R.string.confirm_connect));
        alertDialog.setPositiveButton(getString(android.R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                startConnection();
            }
        });
        alertDialog.setNegativeButton(getString(android.R.string.cancel), null);
        alertDialog.create().show();
    }

    @Override
    protected void onResume() {
        super.onResume();

        startService(UsbService.class, usbConnection);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // delayed start so that UI has enough time to initialize
                BlockchainService.start(WalletActivity.this, true);
            }
        }, 1000);
    }

    public void prepareDataToBroadcast(String data) {
        tempReceivedData.append(data);
        if(tempReceivedData.length() >= 128) {
            receivedData.insert(0, tempReceivedData);
            Log.d("received data length", "Complete signature received");
            Toast.makeText(this, "signature received", Toast.LENGTH_SHORT).show();
            tempReceivedData.delete(0, tempReceivedData.length());
            pushTransaction();
        } else {
            Toast.makeText(this, "incomplete signature", Toast.LENGTH_SHORT).show();
        }
    }

    private void pushTransaction() {

        android.util.Log.d(TAG, "PushTx button clicked");

        StringBuilder realTxString = new StringBuilder();
        StringBuilder scriptSig = new StringBuilder();

        int opcode = 47;
        int header = 30;
        int sigLength = 44;
        String integerC = "02";
        int RSLength = 20;
        int pushDataOpCode = 21;
        String sigCode = "01";
        int[] compressedSenderPublicAddress = new int[]{2,129,68,240,97,124,169,244,111,175,34,195,162,170,190,43,90,112,180,27,51,217,67,238,222,171,69,251,14,163,157,176,59};

        scriptSig.append(opcode);
        scriptSig.append(header);
        scriptSig.append(sigLength);
        scriptSig.append(integerC);
        scriptSig.append(RSLength);
        receivedData.insert(64, "0220");
        scriptSig.append(receivedData);
        scriptSig.append(sigCode);
        scriptSig.append(pushDataOpCode);

        realTxString.append(wallet.getRawTx());

        //Delete sighash
        realTxString.delete(288,296);
        //Replace script length
        realTxString.replace(82, 84, "6a");

        int temp1 = Integer.parseInt(receivedData.substring(0, 2), 16);
        int temp2 = Integer.parseInt(receivedData.substring(68, 70), 16);

        if((Integer.parseInt(receivedData.substring(0, 2), 16) > 127) && (Integer.parseInt(receivedData.substring(68, 70), 16) > 127)) {

            realTxString.replace(82, 84, "6c");
            scriptSig.replace(0, 2, "49");
            scriptSig.replace(4, 6, "46");
            scriptSig.replace(8, 10, "2100");
            scriptSig.replace(78, 80, "2100");

        } else if(Integer.parseInt(receivedData.substring(0, 2), 16) > 127) {

            realTxString.replace(82, 84, "6b");
            scriptSig.replace(0, 2, "48");
            scriptSig.replace(4, 6, "45");
            scriptSig.replace(8, 10, "2100");

        } else if(Integer.parseInt(receivedData.substring(68, 70), 16) > 127) {

            realTxString.replace(82, 84, "6b");
            scriptSig.replace(0, 2, "48");
            scriptSig.replace(4, 6, "45");
            scriptSig.replace(76, 78, "2100");
        }

        receivedData.delete(0, receivedData.length());

        for(int i = 0; i < compressedSenderPublicAddress.length; i++) {
            scriptSig.append(String.format("%02x", compressedSenderPublicAddress[i]));
        }

        //Replace scriptpubkey with Received data
        realTxString.replace(84,134, scriptSig.toString());

        PushTx pushTx = new PushTx(realTxString.toString());

        new ClientBuilder(Constants.BASE_URL_PUSHTX).getBlockchainApi().pushTx(pushTx, "2f0a91ede7634dcfa99291e97146ddd8").enqueue(new Callback<String>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                try {
                    String callObject = call.request().toString();
                    String callbody = call.request().body().toString();
                    String responseTxObject  = response.body();
//                            Log.d("response from bc", responseTxObject);
                    if(response.code() == 201) {
                        Toast.makeText(getApplicationContext(),
                                "Pushtx successful", Toast.LENGTH_SHORT).show();
                    }
                } catch (NullPointerException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                t.printStackTrace();
                Toast.makeText(getApplicationContext(), "Push successful", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onDestroy() {
        if(isConnect) {
            stopConnection();
        }

        unbindService(usbConnection);
        unregisterReceiver(mUsbReceiver);

        super.onDestroy();
    }

    private void stopConnection() {
        usbService.setHandler(null);
        isConnect = false;
        Toast.makeText(getApplicationContext(),
                getString(R.string.stop_connection),Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);

        super.onPause();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        handleIntent(intent);
    }

    private void handleIntent(final Intent intent) {
        final String action = intent.getAction();

        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            final String inputType = intent.getType();
            final NdefMessage ndefMessage = (NdefMessage) intent
                    .getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)[0];
            final byte[] input = Nfc.extractMimePayload(Constants.MIMETYPE_TRANSACTION, ndefMessage);

            new BinaryInputParser(inputType, input) {
                @Override
                protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                    cannotClassify(inputType);
                }

                @Override
                protected void error(final int messageResId, final Object... messageArgs) {
                    dialog(WalletActivity.this, null, 0, messageResId, messageArgs);
                }
            }.parse();
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_SCAN) {
            if (resultCode == Activity.RESULT_OK) {
                final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);

                new StringInputParser(input) {
                    @Override
                    protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                        SendCoinsActivity.start(WalletActivity.this, paymentIntent);
                    }

                    @Override
                    protected void handlePrivateKey(final VersionedChecksummedBytes key) {
                        if (Constants.ENABLE_SWEEP_WALLET)
                            SweepWalletActivity.start(WalletActivity.this, key);
                        else
                            super.handlePrivateKey(key);
                    }

                    @Override
                    protected void handleDirectTransaction(final Transaction tx) throws VerificationException {
                        application.processDirectTransaction(tx);
                    }

                    @Override
                    protected void error(final int messageResId, final Object... messageArgs) {
                        dialog(WalletActivity.this, null, R.string.button_scan, messageResId, messageArgs);
                    }
                }.parse();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, intent);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.wallet_options, menu);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        super.onPrepareOptionsMenu(menu);

        final Resources res = getResources();
        final String externalStorageState = Environment.getExternalStorageState();

        menu.findItem(R.id.wallet_options_exchange_rates)
                .setVisible(Constants.ENABLE_EXCHANGE_RATES && res.getBoolean(R.bool.show_exchange_rates_option));
        menu.findItem(R.id.wallet_options_sweep_wallet).setVisible(Constants.ENABLE_SWEEP_WALLET);
        menu.findItem(R.id.wallet_options_restore_wallet)
                .setEnabled(Environment.MEDIA_MOUNTED.equals(externalStorageState)
                        || Environment.MEDIA_MOUNTED_READ_ONLY.equals(externalStorageState));
        menu.findItem(R.id.wallet_options_backup_wallet)
                .setEnabled(Environment.MEDIA_MOUNTED.equals(externalStorageState));
        final MenuItem encryptKeysOption = menu.findItem(R.id.wallet_options_encrypt_keys);
        encryptKeysOption.setTitle(wallet.isEncrypted() ? R.string.wallet_options_encrypt_keys_change
                : R.string.wallet_options_encrypt_keys_set);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case R.id.wallet_options_request:
            handleRequestCoins();
            return true;

        case R.id.wallet_options_send:
            handleSendCoins();
            return true;

        case R.id.wallet_options_scan:
            handleScan();
            return true;

        case R.id.wallet_options_address_book:
            AddressBookActivity.start(this);
            return true;

        case R.id.wallet_options_exchange_rates:
            startActivity(new Intent(this, ExchangeRatesActivity.class));
            return true;

        case R.id.wallet_options_sweep_wallet:
            SweepWalletActivity.start(this);
            return true;

        case R.id.wallet_options_network_monitor:
            startActivity(new Intent(this, NetworkMonitorActivity.class));
            return true;

        case R.id.wallet_options_restore_wallet:
            handleRestoreWallet();
            return true;

        case R.id.wallet_options_backup_wallet:
            handleBackupWallet();
            return true;

        case R.id.wallet_options_encrypt_keys:
            handleEncryptKeys();
            return true;

        case R.id.wallet_options_preferences:
            startActivity(new Intent(this, PreferenceActivity.class));
            return true;

        case R.id.wallet_options_safety:
            HelpDialogFragment.page(getSupportFragmentManager(), R.string.help_safety);
            return true;

        case R.id.wallet_options_technical_notes:
            HelpDialogFragment.page(getSupportFragmentManager(), R.string.help_technical_notes);
            return true;

        case R.id.wallet_options_report_issue:
            handleReportIssue();
            return true;

        case R.id.wallet_options_help:
            HelpDialogFragment.page(getSupportFragmentManager(), R.string.help_wallet);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void handleRequestCoins() {
        startActivity(new Intent(this, RequestCoinsActivity.class));
    }

    public void handleSendCoins() {
        startActivity(new Intent(this, SendCoinsActivity.class));
    }

    public void handleScan() {
        startActivityForResult(new Intent(this, ScanActivity.class), REQUEST_CODE_SCAN);
    }

    public void handleBackupWallet() {
        BackupWalletDialogFragment.show(getSupportFragmentManager());
    }

    public void handleRestoreWallet() {
        RestoreWalletDialogFragment.show(getSupportFragmentManager());
    }

    public void handleEncryptKeys() {
        EncryptKeysDialogFragment.show(getSupportFragmentManager());
    }

    private void handleReportIssue() {
        ReportIssueDialogFragment.show(getSupportFragmentManager(), R.string.report_issue_dialog_title_issue,
                R.string.report_issue_dialog_message_issue, Constants.REPORT_SUBJECT_ISSUE, null);
    }

    private void checkSavedCrashTrace() {
        if (CrashReporter.hasSavedCrashTrace())
            ReportIssueDialogFragment.show(getSupportFragmentManager(), R.string.report_issue_dialog_title_crash,
                    R.string.report_issue_dialog_message_crash, Constants.REPORT_SUBJECT_CRASH, null);
    }
}
