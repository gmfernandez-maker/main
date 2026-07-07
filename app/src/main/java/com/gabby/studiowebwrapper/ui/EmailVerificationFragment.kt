package com.gabby.studiowebwrapper.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.databinding.FragmentEmailVerificationBinding
import android.net.Uri

class EmailVerificationFragment : Fragment() {

    interface Callbacks {
        fun navigateToLogin()
        fun navigateBack()
    }

    private var callbacks: Callbacks? = null
    private var binding: FragmentEmailVerificationBinding? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentEmailVerificationBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val email = requireArguments().getString(ARG_EMAIL).orEmpty()
        binding?.apply {
            emailValueText.text = if (email.isBlank()) getString(R.string.email_verification_generic_email) else email
            statusText.text = if (email.isBlank()) {
                getString(R.string.email_verification_message_generic)
            } else {
                getString(R.string.email_verification_message, email)
            }

            openEmailButton.setOnClickListener {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_EMAIL)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { startActivity(intent) }
                    .onFailure {
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")))
                    }
            }

            loginButton.setOnClickListener { callbacks?.navigateToLogin() }
            backButton.setOnClickListener { callbacks?.navigateBack() }
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

    companion object {
        private const val ARG_EMAIL = "arg_email"

        fun newInstance(email: String): EmailVerificationFragment {
            return EmailVerificationFragment().apply {
                arguments = Bundle().apply { putString(ARG_EMAIL, email) }
            }
        }
    }
}