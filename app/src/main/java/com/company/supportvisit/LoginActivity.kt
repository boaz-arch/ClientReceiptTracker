package com.company.supportvisit

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.company.supportvisit.databinding.ActivityLoginBinding
import com.company.supportvisit.graph.AuthManager
import com.company.supportvisit.utils.AppConfig

class LoginActivity : AppCompatActivity() {

    private lateinit var binding:
            ActivityLoginBinding

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        AuthManager.initialize(this)

        binding =
            ActivityLoginBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        binding.btnLogin.setOnClickListener {

            val username =
                binding.etUsername.text
                    .toString()

            val password =
                binding.etPassword.text
                    .toString()

            if (AppConfig.LOGIN_ENABLED &&
                AuthManager.login(
                    this,
                    username,
                    password
                )
            ) {

                startActivity(
                    Intent(
                        this,
                        MainActivity::class.java
                    )
                )

                finish()

            } else {

                Toast.makeText(
                    this,
                    "Invalid Username or Password",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}