package com.company.supportvisit

import com.company.supportvisit.ui.applySystemBarInsets
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.company.supportvisit.databinding.ActivityChangeLoginBinding
import com.company.supportvisit.graph.AuthManager

class ChangeLoginActivity : AppCompatActivity() {

    private lateinit var binding:
            ActivityChangeLoginBinding

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        binding =
            ActivityChangeLoginBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        binding.btnSave.setOnClickListener {

            val username =
                binding.etUsername.text
                    .toString()

            val password =
                binding.etPassword.text
                    .toString()

            if (
                username.isBlank() ||
                password.isBlank()
            ) {

                Toast.makeText(
                    this,
                    getString(R.string.complete_all_fields),
                    Toast.LENGTH_LONG
                ).show()

                return@setOnClickListener
            }

            AuthManager.changeLoginInfo(
                this,
                username,
                password
            )

            Toast.makeText(
                this,
                getString(R.string.login_updated),
                Toast.LENGTH_LONG
            ).show()

            finish()
        }
    }
}