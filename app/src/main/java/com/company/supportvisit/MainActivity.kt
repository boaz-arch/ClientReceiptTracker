package com.company.supportvisit

import com.company.supportvisit.ui.applySystemBarInsets
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.company.supportvisit.databinding.ActivityMainBinding
import com.company.supportvisit.graph.AuthManager
import com.company.supportvisit.graph.MicrosoftAuthManager
import com.company.supportvisit.utils.AppConfig
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (AppConfig.LOGIN_ENABLED && !AuthManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        binding.tvUser.setText(R.string.loading_user)

        binding.btnNewVisit.setOnClickListener {
            startActivity(Intent(this, NewVisitActivity::class.java))
        }
        binding.btnPastVisits.setOnClickListener {
            startActivity(PastVisitsActivity.intent(this))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MicrosoftAuthManager.refreshAccount()
                MicrosoftAuthManager.accountEmail.collect { email ->
                    binding.tvUser.text = if (email.isNullOrBlank()) {
                        getString(R.string.signed_in_guest)
                    } else {
                        getString(R.string.signed_in_as, email.substringBefore("@"))
                    }
                }
            }
        }
    }
}
