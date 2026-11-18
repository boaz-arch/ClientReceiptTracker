package com.company.supportvisit

import android.os.Bundle
import com.company.supportvisit.plans.PlanManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import android.util.Patterns
import com.company.supportvisit.databinding.ActivityTeamMembersBinding
import com.company.supportvisit.databinding.DialogInviteBinding
import com.company.supportvisit.databinding.ItemMemberBinding
import com.company.supportvisit.graph.GraphException
import java.util.Locale
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
        binding.btnInvite.setOnClickListener { invite() }
        load()
    }

    private fun load() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val api = TeamRepository.api(this@TeamMembersActivity) ?: error("No server")
                    Triple(api.members(), api.removedMembers(), api.invites())
                }
            }
            binding.swipeRefresh.isRefreshing = false
            result.onSuccess { (active, removed, invites) ->
                show(active, removed)
                showInvites(invites)
            }.onFailure {
                Log.w(TAG, "Loading members failed")
                binding.tvError.isVisible = true
            }
        }
    }

    /** People join by signing in with the invited email address and accepting. */
    private fun invite() {
        if (!PlanManager.canAddMember(this, TeamSession.memberCount(this))) {
            PlanManager.showUpgrade(this)
            return
        }
        val dialog = DialogInviteBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(R.string.invite_member)
            .setView(dialog.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.invite_send) { _, _ ->
                val email = dialog.etEmail.text.toString().trim()
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    Toast.makeText(this, R.string.error_invalid_email, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val role = when (dialog.rgRole.checkedRadioButtonId) {
                    R.id.rbAdmin -> Role.ADMIN
                    R.id.rbManager -> Role.MANAGER
                    else -> Role.TECHNICIAN
                }
                lifecycleScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            TeamRepository.api(this@TeamMembersActivity)!!.invite(email, role.name, Locale.getDefault().language)
                        }
                    }
                    val message = result.fold(
                        onSuccess = { emailed -> getString(if (emailed) R.string.invite_sent else R.string.invite_saved_not_emailed, email) },
                        onFailure = { e ->
                            getString(if ((e as? GraphException)?.code == 409) R.string.invite_already_member else R.string.member_change_failed)
                        }
                    )
                    Toast.makeText(this@TeamMembersActivity, message, Toast.LENGTH_LONG).show()
                    load()
                }
            }
            .show()
    }

    private fun showInvites(invites: List<JSONObject>) {
        binding.headerInvites.isVisible = invites.isNotEmpty()
        binding.listInvites.removeAllViews()
        invites.forEach { inv ->
            val row = ItemMemberBinding.inflate(layoutInflater, binding.listInvites, true)
            val email = inv.optString("email")
            row.tvName.text = email
            row.tvEmail.isVisible = false
            row.tvRole.text = roleLabel(Role.from(inv.optString("role")))
            row.root.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(email)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.invite_cancel) { _, _ -> act { it.cancelInvite(email) } }
                    .show()
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
