package com.ivan.pomodoro.ui.plans

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ivan.pomodoro.PomodoroApp
import com.ivan.pomodoro.data.FocusRepository
import com.ivan.pomodoro.data.Prefs
import com.ivan.pomodoro.data.entity.FocusPlan
import com.ivan.pomodoro.databinding.FragmentPlansBinding
import kotlinx.coroutines.launch

class PlansFragment : Fragment() {

    private var _binding: FragmentPlansBinding? = null
    private val binding get() = _binding!!

    private val repository: FocusRepository by lazy {
        (requireActivity().application as PomodoroApp).repository
    }
    private val prefs: Prefs by lazy { Prefs(requireContext()) }

    private lateinit var adapter: PlanAdapter
    private var plans: List<FocusPlan> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlansBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = PlanAdapter(
            onSelect = { plan -> selectPlan(plan) },
            onMore = { anchor, plan -> showMenu(anchor, plan) }
        )
        binding.rvPlans.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPlans.adapter = adapter

        binding.fabAdd.setOnClickListener { openEditor(0L) }
        binding.btnEmptyCreate.setOnClickListener { openEditor(0L) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observePlans().collect { list ->
                    plans = list
                    val resolved = list.firstOrNull { it.id == prefs.selectedPlanId }
                        ?: list.firstOrNull()
                    if (resolved != null && resolved.id != prefs.selectedPlanId) {
                        prefs.selectedPlanId = resolved.id
                    }
                    adapter.currentPlanId = resolved?.id ?: 0L
                    adapter.submitList(list)
                    binding.emptyState.isVisible = list.isEmpty()
                    binding.rvPlans.isVisible = list.isNotEmpty()
                }
            }
        }
    }

    private fun selectPlan(plan: FocusPlan) {
        prefs.selectedPlanId = plan.id
        adapter.currentPlanId = plan.id
        adapter.notifyDataSetChanged()
        Toast.makeText(requireContext(), "已选择「${plan.name}」", Toast.LENGTH_SHORT).show()
    }

    private fun showMenu(anchor: View, plan: FocusPlan) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "编辑")
        popup.menu.add(0, 2, 1, "删除")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    openEditor(plan.id)
                    true
                }

                2 -> {
                    confirmDelete(plan)
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDelete(plan: FocusPlan) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除计划")
            .setMessage("确定要删除「${plan.name}」吗？已有的专注记录会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.deletePlan(plan.id)
                    if (prefs.selectedPlanId == plan.id) prefs.selectedPlanId = 0L
                    Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun openEditor(planId: Long) {
        startActivity(
            Intent(requireContext(), PlanEditActivity::class.java)
                .putExtra(PlanEditActivity.EXTRA_PLAN_ID, planId)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
