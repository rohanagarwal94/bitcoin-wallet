package org.bitcoinj.core;

import java.io.Serializable;

public class UnSignTransaction implements Serializable {

    private static final long serialVersionUID = 1L;

    public UnSignTransaction(Transaction tx, String address) {
        this.mTx = tx;
        this.mAddress = address;
    }

    private Transaction mTx;

    private String mAddress;

    public Transaction getTx() {
        return mTx;
    }

    public void setTx(Transaction mTx) {
        this.mTx = mTx;
    }

    public String getAddress() {
        return mAddress;
    }

    public void setAddress(String mAddress) {
        this.mAddress = mAddress;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof UnSignTransaction) {
            UnSignTransaction unSignTransaction = (UnSignTransaction) o;
            return compareString(getAddress(),
                    unSignTransaction.getAddress());
        }
        return super.equals(o);
    }

    private boolean compareString(String str, String other) {
        if (str == null) {
            return other == null;
        } else {
            return other != null && str.equals(other);
        }

    }

}