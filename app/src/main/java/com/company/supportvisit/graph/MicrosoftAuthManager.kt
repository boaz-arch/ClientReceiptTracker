package com.company.supportvisit.graph

import android.app.Activity
import com.company.supportvisit.R
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException

object MicrosoftAuthManager {

    private var app:
            ISingleAccountPublicClientApplication? =
        null

    private var email = ""

    private var accessToken = ""

    private val scopes = arrayOf(
        "User.Read",
        "Mail.Send",
        "Files.ReadWrite"
    )

    fun isConnected(): Boolean {

        return email.isNotBlank()
    }

    fun getEmail(): String {

        return email
    }

    fun getToken(): String {

        return accessToken
    }

    fun connect(

        activity: Activity,

        onSuccess: (String) -> Unit,

        onError: (String) -> Unit

    ) {

        PublicClientApplication
            .createSingleAccountPublicClientApplication(

                activity,

                R.raw.msal_config,

                object :
                    IPublicClientApplication.ISingleAccountApplicationCreatedListener {

                    override fun onCreated(
                        application:
                        ISingleAccountPublicClientApplication
                    ) {

                        app = application

                        application.getCurrentAccountAsync(

                            object :
                                ISingleAccountPublicClientApplication.CurrentAccountCallback {

                                override fun onAccountLoaded(
                                    account: IAccount?
                                ) {

                                    if (account != null) {

                                        email =
                                            account.username

                                        application.acquireTokenSilentAsync(

                                            scopes,

                                            account.authority,

                                            object :
                                                com.microsoft.identity.client.SilentAuthenticationCallback {

                                                override fun onSuccess(
                                                    authenticationResult:
                                                    IAuthenticationResult
                                                ) {

                                                    accessToken =
                                                        authenticationResult.accessToken

                                                    onSuccess(
                                                        email
                                                    )
                                                }

                                                override fun onError(
                                                    exception:
                                                    MsalException
                                                ) {

                                                    onError(
                                                        exception.message
                                                            ?: "Token Error"
                                                    )
                                                }
                                            }
                                        )

                                        return
                                    }

                                    application.signIn(

                                        activity,

                                        null,

                                        scopes,

                                        object :
                                            com.microsoft.identity.client.AuthenticationCallback {

                                            override fun onSuccess(
                                                result:
                                                IAuthenticationResult
                                            ) {

                                                email =
                                                    result.account.username

                                                accessToken =
                                                    result.accessToken

                                                onSuccess(
                                                    email
                                                )
                                            }

                                            override fun onError(
                                                exception:
                                                MsalException
                                            ) {

                                                val detailedError =
                                                    """
                                                    Type:
                                                    ${exception.javaClass.name}
                                                    
                                                    Message:
                                                    ${exception.message}
                                                    
                                                    Details:
                                                    ${exception.localizedMessage}
                                                    """.trimIndent()

                                                onError(
                                                    detailedError
                                                )
                                            }

                                            override fun onCancel() {

                                                onError(
                                                    "Login Cancelled"
                                                )
                                            }
                                        }
                                    )
                                }

                                override fun onAccountChanged(
                                    priorAccount: IAccount?,
                                    currentAccount: IAccount?
                                ) {
                                }

                                override fun onError(
                                    exception:
                                    MsalException
                                ) {

                                    val detailedError =
                                        """
                                        Type:
                                        ${exception.javaClass.name}
                                        
                                        Message:
                                        ${exception.message}
                                        
                                        Details:
                                        ${exception.localizedMessage}
                                        """.trimIndent()

                                    onError(
                                        detailedError
                                    )
                                }
                            }
                        )
                    }



                    override fun onError(
                        exception:
                        MsalException
                    ) {

                        val detailedError =
                            """
                            Type:
                            ${exception.javaClass.name}
                            
                            Message:
                            ${exception.message}
                            
                            Details:
                            ${exception.localizedMessage}
                            """.trimIndent()

                        onError(
                            detailedError
                        )
                    }
                }
            )
    }


    fun refreshAccount(
        activity: Activity,
        onDone: () -> Unit
    ) {

        PublicClientApplication
            .createSingleAccountPublicClientApplication(

                activity,

                R.raw.msal_config,

                object :
                    IPublicClientApplication
                    .ISingleAccountApplicationCreatedListener {

                    override fun onCreated(
                        application:
                        ISingleAccountPublicClientApplication
                    ) {

                        app = application

                        application.getCurrentAccountAsync(

                            object :
                                ISingleAccountPublicClientApplication.CurrentAccountCallback {

                                override fun onAccountLoaded(
                                    account: IAccount?
                                ) {

                                    email =
                                        account?.username ?: ""

                                    onDone()
                                }

                                override fun onAccountChanged(
                                    priorAccount: IAccount?,
                                    currentAccount: IAccount?
                                ) {
                                }

                                override fun onError(
                                    exception:
                                    MsalException
                                ) {

                                    email = ""

                                    onDone()
                                }
                            }
                        )
                    }

                    override fun onError(
                        exception:
                        MsalException
                    ) {

                        email = ""

                        onDone()
                    }
                }
            )
    }


    fun disconnect() {

        app?.signOut(

            object :
                ISingleAccountPublicClientApplication.SignOutCallback {

                override fun onSignOut() {

                    email = ""

                    accessToken = ""
                }

                override fun onError(
                    exception:
                    MsalException
                ) {

                    exception.printStackTrace()
                }
            }
        )
    }
}