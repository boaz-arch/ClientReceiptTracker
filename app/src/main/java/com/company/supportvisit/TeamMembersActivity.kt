package com.company.supportvisit

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.databinding.ActivityTeamMembersBinding
import com.company.supportvisit.databinding.ItemMemberBinding
import com.company.supportvisit.remote.ServerApi
import com.company.supportvisit.team.Role
import com.company.supportvisit.team.TeamRepository
import com.company.supportvisit.team.TeamSession
import com.company.supportvisit.ui.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Admin: who is in the company. People join by signing in with their work account; here an admin
 * sets their role, removes them (they can no longer sync) or lets a removed person back in.
 */
class TeamMembersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeamMembersBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeamMembersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        if (!TeamSession.role(this).canManageCompany) {
            finish()
            return
        }
        binding.swipeRefresh.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val api = TeamRepository.api(this@TeamMembersActivity) ?: error("No server")
                    api.members() to api.removedMembers()
                }
            }
            binding.swipeRefresh.isRefreshing = false
            result.onSuccess { (active, removed) -> show(active, removed) }
                .onFailure {
                    Log.w(TAG, "Loading members failed")
                    binding.tvError.isVisible = true
                }
        }
    }

    private fun show(active: List<JSONObject>, removed: List<JSONObject>) {
        binding.tvError.isVisible = false
        val me = TeamSession.userId(this)
        binding.listMembers.removeAllViews()
        active.forEach { m ->
            val row = ItemMemberBinding.inflate(layoutInflater, binding.listMembers, true)
            val userId = m.optString("userId")
            val name = m.optString("name").ifBlank { m.optString("email") }
            row.tvName.text = if (userId == me) getString(R.string.member_you, name) else name
            row.tvEmail.text = m.optString("email")
            val role = Role.from(m.optString("role"))
            row.tvRole.text = roleLabel(role)
            row.root.setOnClickListener { memberActions(userId, name, role, isMe = userId == me) }
        }
        binding.headerRemoved.isVisible = removed.isNotEmpty()
        binding.listRemoved.removeAllViews()
        removed.forEach { m ->
            val row = ItemMemberBinding.inflate(layoutInflater, binding.listRemoved, true)
            val userId = m.optString("userId")
            val name = m.optString("name").ifBlank { m.optString("email") }
            row.tvName.text = name
            row.tvEmail.text = m.optString("email")
            row.tvRole.setText(R.string.member_restore)
            row.root.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage(R.string.member_restore_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.member_restore) { _, _ -> act { it.restoreMember(userId) } }
                    .show()
            }
        }
    }

    private fun memberActions(userId: String, name: String, current: Role, isMe: Boolean) {
        val roles = Role.entries
        val labels = roles.map { roleLabel(it) }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle(name)
            .setSingleChoiceItems(labels, roles.indexOf(current)) { d, which ->
                d.dismiss()
                val chosen = roles[which]
                if (chosen != current) act { it.setRole(userId, chosen.name) }
            }
            .setNegativeButton(R.string.cancel, null)
        if (!isMe) {
            dialog.setNeutralButton(R.string.member_remove) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.member_remove_title, name))
                    .setMessage(R.string.member_remove_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.member_remove) { _, _ -> act { it.removeMember(userId) } }
                    .show()
            }
        }
        dialog.show()
    }

    /** Runs a member change on the server, then reloads (the server refuses removing the last admin). */
    private fun act(change: (ServerApi) -> Unit) {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { change(TeamRepository.api(this@TeamMembersActivity) ?: error("No server")) }
            }.isSuccess
            if (!ok) Toast.makeText(this@TeamMembersActivity, R.string.member_change_failed, Toast.LENGTH_LONG).show()
            // My own role may have changed (I can demote myself while another admin remains).
            TeamRepository.refresh(this@TeamMembersActivity)
            if (!TeamSession.role(this@TeamMembersActivity).canManageCompany) finish() else load()
        }
    }

    private fun roleLabel(role: Role) = getString(
        when (role) {
            Role.ADMIN -> R.string.role_admin
            Role.MANAGER -> R.string.role_manager
            Role.TECHNICIAN -> R.string.role_technician
        }
    )

    companion object {
        private const val TAG = "TeamMembers"
    }
}
