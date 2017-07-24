/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.example.android.fingerprintdialog;

import android.annotation.SuppressLint;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.preference.PreferenceManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

/**
 * A dialog which uses fingerprint APIs to authenticate the user, and falls back to password
 * authentication if fingerprint is not available.
 */
@SuppressLint("ValidFragment")
public class FingerprintDialog extends DialogFragment {
    public static String USE_FINGERPRINT_IN_FUTURE = "use_fingerprint_in_future";
    public static String FINGERPRINT_DIALOG_TAG = "fingerprint_dialog_tag";
    
    public static String SECRET_MESSAGE = "secret_message";
    public static String DEFAULT_KEY_NAME = "default_key_name";
    
    
    KeyStore keyStore;
    KeyGenerator keyGenerator;
    Cipher cipher;
    
    
    Button btPositive, btNeutral, btNegative;
    CheckBox cbFingerprintInFuture;
    
    RelativeLayout rlFingerprint, rlPassword;
    
    EditText etPassword;
    
    TextView tv2;
    
    
    private AuthenticationType authenticationType = AuthenticationType.FINGERPRINT;
    
    private FingerprintManager.CryptoObject mCryptoObject;
    private FingerprintUiHelper mFingerprintUiHelper;
    private MainActivity mActivity;
    
    private InputMethodManager mInputMethodManager;
    private SharedPreferences mSharedPreferences;
    
    
    @Override
    public void onCreate (Bundle savedInstanceState) {
        super.onCreate (savedInstanceState);
        // Do not create a new Fragment when the Activity is re-created such as orientation changes.
        setRetainInstance (true);
        setStyle (DialogFragment.STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog);
    }
    
    @Override
    public View onCreateView (LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        getDialog ().setTitle (getString (R.string.sign_in));
        View v = inflater.inflate (R.layout.fingerprint_dialog, container, false);
        
        initView (v);
        
        btNegative.setOnClickListener (new View.OnClickListener () {
            @Override
            public void onClick (View view) {
                dismiss ();
            }
        });
        
        btNeutral.setOnClickListener (new View.OnClickListener () {
            @Override
            public void onClick (View view) {
                if (authenticationType == AuthenticationType.FINGERPRINT) {
                    showPasswordLayout ();
                } else {
                    verifyPassword ();
                }
            }
        });
        etPassword.setOnEditorActionListener (new TextView.OnEditorActionListener () {
            @Override
            public boolean onEditorAction (TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    verifyPassword ();
                    return true;
                }
                return false;
            }
        });
        
        mFingerprintUiHelper = new FingerprintUiHelper (
                mActivity.getSystemService (FingerprintManager.class),
                (ImageView) v.findViewById (R.id.ivIcon),
                (TextView) v.findViewById (R.id.tvMessage));
        
        
        updateAuthenticationType ();
        
        // If fingerprint authentication is not available, switch immediately to the backup
        // (password) screen.
        if (! mFingerprintUiHelper.isFingerprintAuthAvailable ()) {
            showPasswordLayout ();
        }
        return v;
    }
    
    public void initView(View v){
        btPositive = (Button) v.findViewById (R.id.btPositive);
        btNegative = (Button) v.findViewById (R.id.btNegative);
        btNeutral = (Button) v.findViewById (R.id.btNeutral);
        cbFingerprintInFuture = (CheckBox) v.findViewById (R.id.cbFingerprintInFuture);
        rlFingerprint = (RelativeLayout) v.findViewById (R.id.rlFingerprint);
        rlPassword = (RelativeLayout) v.findViewById (R.id.rlPassword);
        etPassword = (EditText) v.findViewById (R.id.etPassword);
        tv2 = (TextView) v.findViewById (R.id.tv2);
    }
    
    public void initListener(){
        
    }
    
    
    @Override
    public void onResume () {
        super.onResume ();
        if (authenticationType == AuthenticationType.FINGERPRINT) {
            mFingerprintUiHelper.startListening (mCryptoObject);
        }
    }
    
    public void setAuthenticationType (AuthenticationType authenticationType) {
        this.authenticationType = authenticationType;
    }
    
    @Override
    public void onPause () {
        super.onPause ();
        mFingerprintUiHelper.stopListening ();
    }
    
    @Override
    public void onAttach (Context context) {
        super.onAttach (context);
        mActivity = (MainActivity) getActivity ();
        mInputMethodManager = context.getSystemService (InputMethodManager.class);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences (context);
    }
    
    /**
     * Sets the crypto object to be passed in when authenticating with fingerprint.
     */
    public void setCryptoObject () {
        mCryptoObject = new FingerprintManager.CryptoObject (cipher);//cryptoObject;
    }
    
    /**
     * Switches to backup (password) screen. This either can happen when fingerprint is not
     * available or the user chooses to use the password authentication method by pressing the
     * button. This can also happen when the user had too many fingerprint attempts.
     */
    private void showPasswordLayout () {
        authenticationType = AuthenticationType.PASSWORD;
        updateAuthenticationType ();
        etPassword.requestFocus ();
        // Show the keyboard.
        etPassword.postDelayed (mShowKeyboardRunnable, 500);
        
        // Fingerprint is not used anymore. Stop listening for it.
        mFingerprintUiHelper.stopListening ();
    }
    
    /**
     * Checks whether the current entered password is correct, and dismisses the the dialog and
     * let's the activity know about the result.
     */
    private void verifyPassword () {
        if (! checkPassword (etPassword.getText ().toString ())) {
            return;
        }
        if (authenticationType == AuthenticationType.NEW_FINGERPRINT_ENROLLED) {
            SharedPreferences.Editor editor = mSharedPreferences.edit ();
            editor.putBoolean (getString (R.string.use_fingerprint_to_authenticate_key), cbFingerprintInFuture.isChecked ());
            editor.apply ();
            
            if (cbFingerprintInFuture.isChecked ()) {
                // Re-create the key so that fingerprints including new ones are validated.
                createKey (DEFAULT_KEY_NAME);
                authenticationType = AuthenticationType.FINGERPRINT;
            }
        }
        etPassword.setText ("");
        mActivity.onSuccessfulAuthentication (false /* without Fingerprint */, null);
        dismiss ();
    }
    
    /**
     * @return true if {@code password} is correct, false otherwise
     */
    private boolean checkPassword (String password) {
        // Assume the password is always correct.
        // In the real world situation, the password needs to be verified in the server side.
        return password.length () > 0;
    }
    
    private final Runnable mShowKeyboardRunnable = new Runnable () {
        @Override
        public void run () {
            mInputMethodManager.showSoftInput (etPassword, 0);
        }
    };
    
    private void updateAuthenticationType () {
        switch (authenticationType) {
            case FINGERPRINT:
                btNeutral.setText ("PASSWORD");
                rlFingerprint.setVisibility (View.VISIBLE);
                rlPassword.setVisibility (View.GONE);
                break;
            case NEW_FINGERPRINT_ENROLLED:
                // Intentional fall through
            case PASSWORD:
                btNeutral.setText ("FINGERPRINT");
                tv2.setText ("Enter password to continue");
                rlFingerprint.setVisibility (View.GONE);
                rlPassword.setVisibility (View.VISIBLE);
                if (authenticationType == AuthenticationType.NEW_FINGERPRINT_ENROLLED) {
                    btNeutral.setEnabled (false);
                    tv2.setText ("New fingerprint enrolled, password compulsory");
                    cbFingerprintInFuture.setVisibility (View.VISIBLE);
                }
                break;
        }
    }
    
    public enum AuthenticationType {
        FINGERPRINT,
        NEW_FINGERPRINT_ENROLLED,
        PASSWORD
    }
    
    public class FingerprintUiHelper extends FingerprintManager.AuthenticationCallback {
        
        private static final long ERROR_TIMEOUT_MILLIS = 1600;
        private static final long SUCCESS_DELAY_MILLIS = 1300;
        
        private final FingerprintManager mFingerprintManager;
        private final ImageView mIcon;
        private final TextView mErrorTextView;
        private CancellationSignal mCancellationSignal;
        
        private boolean mSelfCancelled;
        
        /**
         * Constructor for {@link FingerprintUiHelper}.
         */
        FingerprintUiHelper (FingerprintManager fingerprintManager,
                             ImageView icon, TextView errorTextView) {
            mFingerprintManager = fingerprintManager;
            mIcon = icon;
            mErrorTextView = errorTextView;
        }
        
        public boolean isFingerprintAuthAvailable () {
            // The line below prevents the false positive inspection from Android Studio
            // noinspection ResourceType
            return mFingerprintManager.isHardwareDetected ()
                    && mFingerprintManager.hasEnrolledFingerprints ();
        }
        
        public void startListening (FingerprintManager.CryptoObject cryptoObject) {
            if (! isFingerprintAuthAvailable ()) {
                return;
            }
            mCancellationSignal = new CancellationSignal ();
            mSelfCancelled = false;
            // The line below prevents the false positive inspection from Android Studio
            // noinspection ResourceType
            mFingerprintManager
                    .authenticate (cryptoObject, mCancellationSignal, 0 /* flags */, this, null);
            mIcon.setImageResource (R.drawable.ic_fp_40px);
        }
        
        public void stopListening () {
            if (mCancellationSignal != null) {
                mSelfCancelled = true;
                mCancellationSignal.cancel ();
                mCancellationSignal = null;
            }
        }
        
        @Override
        public void onAuthenticationError (int errMsgId, CharSequence errString) {
            if (! mSelfCancelled) {
                showError (errString);
                mIcon.postDelayed (new Runnable () {
                    @Override
                    public void run () {
                        onError ();
                    }
                }, ERROR_TIMEOUT_MILLIS);
            }
        }
        
        @Override
        public void onAuthenticationHelp (int helpMsgId, CharSequence helpString) {
            showError (helpString);
        }
        
        @Override
        public void onAuthenticationFailed () {
            showError (mIcon.getResources ().getString (
                    R.string.fingerprint_not_recognized));
        }
        
        @Override
        public void onAuthenticationSucceeded (FingerprintManager.AuthenticationResult result) {
            mErrorTextView.removeCallbacks (mResetErrorTextRunnable);
            mIcon.setImageResource (R.drawable.ic_fingerprint_success);
            mErrorTextView.setTextColor (
                    mErrorTextView.getResources ().getColor (R.color.success_color, null));
            mErrorTextView.setText (
                    mErrorTextView.getResources ().getString (R.string.fingerprint_success));
            mIcon.postDelayed (new Runnable () {
                @Override
                public void run () {
                    onAuthenticated ();
                }
            }, SUCCESS_DELAY_MILLIS);
        }
        
        private void showError (CharSequence error) {
            mIcon.setImageResource (R.drawable.ic_fingerprint_error);
            mErrorTextView.setText (error);
            mErrorTextView.setTextColor (
                    mErrorTextView.getResources ().getColor (R.color.warning_color, null));
            mErrorTextView.removeCallbacks (mResetErrorTextRunnable);
            mErrorTextView.postDelayed (mResetErrorTextRunnable, ERROR_TIMEOUT_MILLIS);
        }
    
        void onAuthenticated (){
            mActivity.onSuccessfulAuthentication (true /* withFingerprint */, mCryptoObject);
            dismiss ();
        }
    
        void onError (){
            showPasswordLayout ();
        }
        
        private Runnable mResetErrorTextRunnable = new Runnable () {
            @Override
            public void run () {
                mErrorTextView.setTextColor (
                        mErrorTextView.getResources ().getColor (R.color.hint_color, null));
                mErrorTextView.setText (
                        mErrorTextView.getResources ().getString (R.string.fingerprint_hint));
                mIcon.setImageResource (R.drawable.ic_fp_40px);
            }
        };
    }
    
    public void createKey (String keyName) {
        // The enrolling flow for fingerprint. This is where you ask the user to set up fingerprint
        // for your flow. Use of keys is necessary if you need to know if the set of
        // enrolled fingerprints has changed.
        try {
            keyStore.load (null);
            // Set the alias of the entry in Android KeyStore where the key will appear
            // and the constrains (purposes) in the constructor of the Builder
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder (keyName,
                    KeyProperties.PURPOSE_ENCRYPT |
                            KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes (KeyProperties.BLOCK_MODE_CBC)
                    // Require the user to authenticate with a fingerprint to authorize every use of the key
                    .setUserAuthenticationRequired (true)
                    .setEncryptionPaddings (KeyProperties.ENCRYPTION_PADDING_PKCS7);
            
            keyGenerator.init (builder.build ());
            keyGenerator.generateKey ();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException
                | CertificateException | IOException e) {
            throw new RuntimeException (e);
        }
    }
    
    public boolean initDialog () {
        try {
            keyStore = KeyStore.getInstance ("AndroidKeyStore");
        } catch (KeyStoreException e) {
            throw new RuntimeException ("Failed to get an instance of KeyStore", e);
        }
        try {
            keyGenerator = KeyGenerator.getInstance (KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new RuntimeException ("Failed to get an instance of KeyGenerator", e);
        }
        try {
            cipher = Cipher.getInstance (
                    KeyProperties.KEY_ALGORITHM_AES + "/"
                            + KeyProperties.BLOCK_MODE_CBC + "/"
                            + KeyProperties.ENCRYPTION_PADDING_PKCS7);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new RuntimeException ("Failed to get an instance of Cipher", e);
        }
        createKey (DEFAULT_KEY_NAME);
    
        try {
            keyStore.load (null);
            SecretKey key = (SecretKey) keyStore.getKey (DEFAULT_KEY_NAME, null);
            cipher.init (Cipher.ENCRYPT_MODE, key);
            return true;
        } catch (KeyPermanentlyInvalidatedException e) {
            return false;
        } catch (KeyStoreException | CertificateException | UnrecoverableKeyException | IOException
                | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException ("Failed to initDialog Cipher", e);
        }
    }
    
    public boolean checkFingerprintAvailable (Context context) {
        KeyguardManager keyguardManager = context.getSystemService (KeyguardManager.class);
        FingerprintManager fingerprintManager = context.getSystemService (FingerprintManager.class);
        
        if (! keyguardManager.isKeyguardSecure ()) {
            // Show a message that the user hasn't set up a fingerprint or lock screen.
            Toast.makeText (context,
                    "Secure lock screen hasn't set up.\n"
                            + "Go to 'Settings -> Security -> Fingerprint' to set up a fingerprint",
                    Toast.LENGTH_LONG).show ();
            return false;
        }
        // The line below prevents the false positive inspection from Android Studio
        // noinspection ResourceType
        if (! fingerprintManager.hasEnrolledFingerprints ()) {
            // This happens when no fingerprints are registered.
            Toast.makeText (context,
                    "Go to 'Settings -> Security -> Fingerprint' and register at least one fingerprint",
                    Toast.LENGTH_LONG).show ();
            return false;
        }
        return true;
    }
    
    public void showFingerprintDialog (Context context, FragmentManager fragmentManager){
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences (context);
        // Set up the crypto object for later. The object will be authenticated by use of the fingerprint.
        if (initDialog ()) {
            // Show the fingerprint dialog. The user has the option to use the fingerprint with crypto, or you can fall back to using a server-side verified password.
            setCryptoObject ();
            boolean useFingerprintPreference = sharedPreferences.getBoolean (FingerprintDialog.USE_FINGERPRINT_IN_FUTURE, true);
            if (useFingerprintPreference) {
                setAuthenticationType (FingerprintDialog.AuthenticationType.FINGERPRINT);
            } else {
                setAuthenticationType (FingerprintDialog.AuthenticationType.PASSWORD);
            }
            show (fragmentManager, FingerprintDialog.FINGERPRINT_DIALOG_TAG);
        } else {
            // This happens if the lock screen has been disabled or or a fingerprint got
            // enrolled. Thus show the dialog to authenticate with their password first
            // and ask the user if they want to authenticate with fingerprints in the
            // future
            setCryptoObject ();
            setAuthenticationType (FingerprintDialog.AuthenticationType.NEW_FINGERPRINT_ENROLLED);
            show (getFragmentManager (), FingerprintDialog.FINGERPRINT_DIALOG_TAG);
        }
    
    
    }
    
    /**
     use this method in calling activity
     */
    /*
    public void onSuccessfulAuthentication (boolean withFingerprint, @Nullable FingerprintManager.CryptoObject cryptoObject) {
        if (withFingerprint) {
            // If the user has authenticated with fingerprint, verify that using cryptography and then show the confirmation message.
            assert cryptoObject != null;
            try {
                byte[] encrypted = cryptoObject.getCipher ().doFinal (FingerprintDialog.SECRET_MESSAGE.getBytes ());
                findViewById (R.id.confirmation_message).setVisibility (View.VISIBLE);
                if (encrypted != null) {
                    TextView v = (TextView) findViewById (R.id.encrypted_message);
                    v.setVisibility (View.VISIBLE);
                    v.setText (Base64.encodeToString (encrypted, 0 *//* flags *//*));
                }
            } catch (BadPaddingException | IllegalBlockSizeException e) {
                Toast.makeText (this, "Failed to encrypt the data with the generated key. "
                        + "Retry the purchase", Toast.LENGTH_LONG).show ();
                Log.e ("TAG", "Failed to encrypt the data with the generated key." + e.getMessage ());
            }
        } else {
            // Authentication happened with backup password. Just show the confirmation message.
        }
    }*/
}