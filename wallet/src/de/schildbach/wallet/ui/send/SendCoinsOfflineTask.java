/*
 * Copyright 2013-2015 the original author or authors.
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

package de.schildbach.wallet.ui.send;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.signers.TransactionSigner;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.CompletionException;
import org.bitcoinj.wallet.Wallet.CouldNotAdjustDownwards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.service.UsbService;
import de.schildbach.wallet.ui.WalletActivity;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.util.concurrent.locks.ReentrantLock;

import static de.schildbach.wallet.ui.WalletActivity.TAG;

/**
 * @author Andreas Schildbach
 */
public abstract class SendCoinsOfflineTask {
    private static Wallet wallet;
    private final Handler backgroundHandler;
    private final Handler callbackHandler;
    protected final ReentrantLock lock = Threading.lock("sendcoinsofflinetask");
    private UsbService usbService;
    private NetworkParameters parameters;
    private Transaction transaction;

    private static final Logger log = LoggerFactory.getLogger(SendCoinsOfflineTask.class);

    public SendCoinsOfflineTask(Wallet wallet, final Handler backgroundHandler, final UsbService usbService, NetworkParameters parameters) {
        this.wallet = wallet;
        this.backgroundHandler = backgroundHandler;
        this.usbService = usbService;
        this.parameters = parameters;
        this.callbackHandler = new Handler(Looper.myLooper());
    }

    public final void sendCoinsOffline(final SendRequest sendRequest) {
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

                try {
                    log.info("sending: {}", sendRequest);

                    lock.lock();
                    try {
                        wallet.sendCoinsOfflineWithHardware(sendRequest); // can take long
                        //TODO : Check both sendrequest.tx and wallet.getrawtx() values
//                        transaction = sendRequest.tx;
                        sendMessageToHardware(wallet.getRawTx());

                        SendCoinsFragment.realTransactionCallback = new SendCoinsFragment.RealTransactionCallback() {
                            @Override
                            public void callbackCall(byte[] realTransaction) {
                                byte[] finalTransaction = new byte[sendRequest.tx.bitcoinSerialize().length];
                                byte[] rawTransaction = sendRequest.tx.bitcoinSerialize();

                                transaction = new Transaction(parameters, realTransaction);
                                wallet.commitTx(transaction);
                            }
                        };

                    } finally {
                        lock.unlock();
                    }

                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onSuccess(transaction);
                        }
                    });
                } catch (final InsufficientMoneyException x) {
                    final Coin missing = x.missing;
                    if (missing != null)
                        log.info("send failed, {} missing", missing.toFriendlyString());
                    else
                        log.info("send failed, insufficient coins");

                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onInsufficientMoney(x.missing);
                        }
                    });
                } catch (final ECKey.KeyIsEncryptedException x) {
                    log.info("send failed, key is encrypted: {}", x.getMessage());

                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onFailure(x);
                        }
                    });
                } catch (final KeyCrypterException x) {
                    log.info("send failed, key crypter exception: {}", x.getMessage());

                    final boolean isEncrypted = wallet.isEncrypted();
                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isEncrypted)
                                onInvalidEncryptionKey();
                            else
                                onFailure(x);
                        }
                    });
                } catch (final CouldNotAdjustDownwards x) {
                    log.info("send failed, could not adjust downwards: {}", x.getMessage());

                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onEmptyWalletFailed();
                        }
                    });
                } catch (final CompletionException x) {
                    log.info("send failed, cannot complete: {}", x.getMessage());

                    callbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onFailure(x);
                        }
                    });
                }
            }
        });
    }

    private void sendMessageToHardware(String msg) {
        //TODO : Change this when scriptkey sign received from hardware
        try {
            usbService.write(msg.getBytes(Constants.CHARSET));
            Log.d(TAG, "SendMessage: " + msg);

        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, e.toString());
        }
    }

    protected abstract void onSuccess(Transaction transaction);

    protected abstract void onInsufficientMoney(Coin missing);

    protected abstract void onInvalidEncryptionKey();

    protected void onEmptyWalletFailed() {
        onFailure(new CouldNotAdjustDownwards());
    }

    protected abstract void onFailure(Exception exception);
}
