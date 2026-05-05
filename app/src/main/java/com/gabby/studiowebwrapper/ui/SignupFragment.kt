package com.gabby.studiowebwrapper.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.NativeRepository
import com.gabby.studiowebwrapper.databinding.FragmentSignupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignupFragment : Fragment() {

    interface Callbacks {
        fun navigateToLogin()
        fun navigateToUpload()
        fun navigateBack()
    }

    private var callbacks: Callbacks? = null
    private var binding: FragmentSignupBinding? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSignupBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding?.apply {
            signupButton.setOnClickListener { submitSignup() }
            loginLink.setOnClickListener { callbacks?.navigateToLogin() }
            backButton.setOnClickListener { callbacks?.navigateBack() }
        }
    }

    private fun submitSignup() {
        val currentBinding = binding ?: return
        val fullName = currentBinding.fullNameInput.text.toString().trim()
        val email = currentBinding.emailInput.text.toString().trim()
        val password = currentBinding.passwordInput.text.toString()

        if (email.isBlank() || password.length < 6) {
            currentBinding.errorText.text = getString(R.string.signup_validation_error)
            currentBinding.errorText.isVisible = true
            return
        }

        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                NativeRepository.signup(requireContext(), fullName, email, password)
            }
            setLoading(false)

            result.onSuccess {
                Toast.makeText(requireContext(), getString(R.string.signup_success), Toast.LENGTH_SHORT).show()
                callbacks?.navigateToUpload()
            }.onFailure {
                currentBinding.errorText.text = it.message ?: getString(R.string.generic_error)
                currentBinding.errorText.isVisible = true
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        binding?.apply {
            progressBar.isVisible = isLoading
            signupButton.isEnabled = !isLoading
            fullNameInput.isEnabled = !isLoading
            emailInput.isEnabled = !isLoading
            passwordInput.isEnabled = !isLoading
            loginLink.isEnabled = !isLoading
            errorText.isVisible = false
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onDetach() {
        callbacks = null
        super.onDetach()
    }
}
