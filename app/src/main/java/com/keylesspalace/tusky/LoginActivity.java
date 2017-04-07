/* Copyright 2017 Andrew Dawson
 *
 * This file is part of Tusky.
 *
 * Tusky is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with Tusky. If
 * not, see <http://www.gnu.org/licenses/>. */

package com.keylesspalace.tusky;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.keylesspalace.tusky.entity.AccessToken;
import com.keylesspalace.tusky.entity.AppCredentials;

import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import butterknife.BindView;
import butterknife.ButterKnife;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class LoginActivity extends AppCompatActivity {
    private static final String TAG = "LoginActivity"; // logging tag
    private static String OAUTH_SCOPES = "read write follow";

    private SharedPreferences preferences;

    private String domain;
    private String clientId;
    private String clientSecret;

    @BindView(R.id.edit_text_domain) EditText editText;
    @BindView(R.id.button_login) Button button;
    @BindView(R.id.whats_an_instance) TextView whatsAnInstance;

    /**
     * Chain together the key-value pairs into a query string, for either appending to a URL or
     * as the content of an HTTP request.
     */
    private static String toQueryString(Map<String, String> parameters) {
        StringBuilder s = new StringBuilder();
        String between = "";
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            s.append(between);
            s.append(Uri.encode(entry.getKey()));
            s.append("=");
            s.append(Uri.encode(entry.getValue()));
            between = "&";
        }
        return s.toString();
    }

    /** Make sure the user-entered text is just a fully-qualified domain name. */
    private static String validateDomain(String s) {
        s = s.replaceFirst("http://", "");
        s = s.replaceFirst("https://", "");
        return s.trim();
    }

    private String getOauthRedirectUri() {
        String scheme = getString(R.string.oauth_scheme);
        String host = getString(R.string.oauth_redirect_host);
        return scheme + "://" + host + "/";
    }

    private void redirectUserToAuthorizeAndLogin(EditText editText) {
        /* To authorize this app and log in it's necessary to redirect to the domain given,
         * activity_login there, and the server will redirect back to the app with its response. */
        String endpoint = MastodonAPI.ENDPOINT_AUTHORIZE;
        String redirectUri = getOauthRedirectUri();
        Map<String, String> parameters = new HashMap<>();
        parameters.put("client_id", clientId);
        parameters.put("redirect_uri", redirectUri);
        parameters.put("response_type", "code");
        parameters.put("scope", OAUTH_SCOPES);
        String url = "https://" + domain + endpoint + "?" + toQueryString(parameters);
        Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (viewIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(viewIntent);
        } else {
            editText.setError(getString(R.string.error_no_web_browser_found));
        }
    }

    private MastodonAPI getApiFor(String domain) {
        Retrofit retrofit = new Retrofit.Builder()
                .client(setSSLFactoryForClient())
                .baseUrl("https://" + domain)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        return retrofit.create(MastodonAPI.class);
    }

    /**
     * Obtain the oauth client credentials for this app. This is only necessary the first time the
     * app is run on a given server instance. So, after the first authentication, they are
     * saved in SharedPreferences and every subsequent run they are simply fetched from there.
     */
    private void onButtonClick(final EditText editText) {
        domain = validateDomain(editText.getText().toString());
        /* Attempt to get client credentials from SharedPreferences, and if not present
         * (such as in the case that the domain has never been accessed before)
         * authenticate with the server and store the received credentials to use next
         * time. */
        String prefClientId = preferences.getString(domain + "/client_id", null);
        String prefClientSecret = preferences.getString(domain + "/client_secret", null);

        if (prefClientId != null && prefClientSecret != null) {
            clientId = prefClientId;
            clientSecret = prefClientSecret;
            redirectUserToAuthorizeAndLogin(editText);
        } else {
            Callback<AppCredentials> callback = new Callback<AppCredentials>() {
                @Override
                public void onResponse(Call<AppCredentials> call, Response<AppCredentials> response) {
                    if (!response.isSuccessful()) {
                        editText.setError(getString(R.string.error_failed_app_registration));
                        Log.e(TAG, "App authentication failed. " + response.message());
                        return;
                    }
                    AppCredentials credentials = response.body();
                    clientId = credentials.clientId;
                    clientSecret = credentials.clientSecret;
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putString(domain + "/client_id", clientId);
                    editor.putString(domain + "/client_secret", clientSecret);
                    editor.apply();
                    redirectUserToAuthorizeAndLogin(editText);
                }

                @Override
                public void onFailure(Call<AppCredentials> call, Throwable t) {
                    editText.setError(getString(R.string.error_failed_app_registration));
                    t.printStackTrace();
                }
            };

            try {
                getApiFor(domain).authenticateApp(getString(R.string.app_name), getOauthRedirectUri(), OAUTH_SCOPES,
                        getString(R.string.app_website)).enqueue(callback);
            } catch (IllegalArgumentException e) {
                editText.setError(getString(R.string.error_invalid_domain));
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("lightTheme", false)) {
            setTheme(R.style.AppTheme_Light);
        }

        setContentView(R.layout.activity_login);
        ButterKnife.bind(this);

        if (savedInstanceState != null) {
            domain = savedInstanceState.getString("domain");
            clientId = savedInstanceState.getString("clientId");
            clientSecret = savedInstanceState.getString("clientSecret");
        } else {
            domain = null;
            clientId = null;
            clientSecret = null;
        }

        preferences = getSharedPreferences(
                getString(R.string.preferences_file_key), Context.MODE_PRIVATE);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onButtonClick(editText);
            }
        });

        final Context context = this;

        whatsAnInstance.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog dialog = new AlertDialog.Builder(context)
                        .setMessage(R.string.dialog_whats_an_instance)
                        .setPositiveButton(R.string.action_close,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        dialog.dismiss();
                                    }
                                })
                        .show();
                TextView textView = (TextView) dialog.findViewById(android.R.id.message);
                textView.setMovementMethod(LinkMovementMethod.getInstance());
            }
        });

        // Apply any updates needed.
        int versionCode = 1;
        try {
            versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "The app version was not found. " + e.getMessage());
        }
        if (preferences.getInt("lastUpdate", 0) != versionCode) {
            SharedPreferences.Editor editor = preferences.edit();
            if (versionCode == 14) {
                /* This version switches the order of scheme and host in the OAuth redirect URI.
                 * But to fix it requires forcing the app to re-authenticate with servers. So, clear
                 * out the stored client id/secret pairs. The only other things that are lost are
                 * "rememberedVisibility", "loggedInUsername", and "loggedInAccountId". */
                editor.clear();
            }
            editor.putInt("lastUpdate", versionCode);
            editor.apply();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("domain", domain);
        outState.putString("clientId", clientId);
        outState.putString("clientSecret", clientSecret);
        super.onSaveInstanceState(outState);
    }

    private void onLoginSuccess(String accessToken) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("domain", domain);
        editor.putString("accessToken", accessToken);
        editor.commit();
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    public static OkHttpClient setSSLFactoryForClient() {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
            };

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager)trustAllCerts[0]);
            builder.hostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });

            OkHttpClient okHttpClient = builder
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
            return okHttpClient;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (domain != null) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString("domain", domain);
            editor.putString("clientId", clientId);
            editor.putString("clientSecret", clientSecret);
            editor.apply();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        /* Check if we are resuming during authorization by seeing if the intent contains the
         * redirect that was given to the server. If so, its response is here! */
        Uri uri = getIntent().getData();
        String redirectUri = getOauthRedirectUri();

        preferences = getSharedPreferences(
                getString(R.string.preferences_file_key), Context.MODE_PRIVATE);

        if (preferences.getString("accessToken", null) != null
                && preferences.getString("domain", null) != null) {
            // We are already logged in, go to MainActivity
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        if (uri != null && uri.toString().startsWith(redirectUri)) {
            // This should either have returned an authorization code or an error.
            String code = uri.getQueryParameter("code");
            String error = uri.getQueryParameter("error");

            if (code != null) {
                /* During the redirect roundtrip this Activity usually dies, which wipes out the
                 * instance variables, so they have to be recovered from where they were saved in
                 * SharedPreferences. */
                domain = preferences.getString("domain", null);
                clientId = preferences.getString("clientId", null);
                clientSecret = preferences.getString("clientSecret", null);
                /* Since authorization has succeeded, the final step to log in is to exchange
                 * the authorization code for an access token. */
                Callback<AccessToken> callback = new Callback<AccessToken>() {
                    @Override
                    public void onResponse(Call<AccessToken> call, Response<AccessToken> response) {
                        if (response.isSuccessful()) {
                            onLoginSuccess(response.body().accessToken);
                        } else {
                            editText.setError(getString(R.string.error_retrieving_oauth_token));
                            Log.e(TAG, String.format("%s %s",
                                    getString(R.string.error_retrieving_oauth_token),
                                    response.message()));
                        }
                    }

                    @Override
                    public void onFailure(Call<AccessToken> call, Throwable t) {
                        editText.setError(getString(R.string.error_retrieving_oauth_token));
                        Log.e(TAG, String.format("%s %s",
                                getString(R.string.error_retrieving_oauth_token),
                                t.getMessage()));
                    }
                };

                getApiFor(domain).fetchOAuthToken(clientId, clientSecret, redirectUri, code,
                        "authorization_code").enqueue(callback);
            } else if (error != null) {
                /* Authorization failed. Put the error response where the user can read it and they
                 * can try again. */
                editText.setError(getString(R.string.error_authorization_denied));
                Log.e(TAG, getString(R.string.error_authorization_denied) + error);
            } else {
                // This case means a junk response was received somehow.
                editText.setError(getString(R.string.error_authorization_unknown));
            }
        }
    }
}
