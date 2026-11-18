package com.company.supportvisit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.databinding.ActivityClientsBinding
import com.company.supportvisit.databinding.ItemClientBinding
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.ui.setUpBackBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The shared client list, with search and add/edit. */
class ClientsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientsBinding

    private val query = MutableStateFlow("")

    private val adapter = ClientAdapter { startActivity(ClientEditActivity.intent(this, it.id)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityClientsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        setUpBackBar()

        binding.rvClients.layoutManager = LinearLayoutManager(this)
        binding.rvClients.adapter = adapter
        binding.etSearch.doAfterTextChanged { query.value = it?.toString().orEmpty().trim() }
        binding.btnAddClient.setOnClickListener { startActivity(ClientEditActivity.intent(this, null)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.clientRepository.observeClients(), query) { clients, q ->
                    if (q.isEmpty()) clients else clients.filter { c ->
                        listOf(c.name, c.contactPerson, c.email, c.phone).any { it.contains(q, ignoreCase = true) }
                    }
                }.collect { clients ->
                    adapter.submitList(clients)
                    binding.tvEmpty.visibility = if (clients.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private class ClientAdapter(
        private val onClick: (ClientEntity) -> Unit
    ) : ListAdapter<ClientEntity, ClientAdapter.ViewHolder>(DIFF) {

        class ViewHolder(val binding: ItemClientBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemClientBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val client = getItem(position)
            holder.binding.tvName.text = client.name
            holder.binding.tvDetails.text = listOf(client.contactPerson, client.email, client.phone)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            holder.binding.root.setOnClickListener { onClick(client) }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ClientEntity>() {
                override fun areItemsTheSame(old: ClientEntity, new: ClientEntity) = old.id == new.id
                override fun areContentsTheSame(old: ClientEntity, new: ClientEntity) = old == new
            }
        }
    }
}
