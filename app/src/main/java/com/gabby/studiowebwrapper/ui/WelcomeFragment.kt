package com.gabby.studiowebwrapper.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.gabby.studiowebwrapper.databinding.FragmentWelcomeBinding

class WelcomeFragment : Fragment() {

    interface Callbacks {
        fun navigateToLogin()
        fun navigateToSignup()
        fun navigateToUpload()
        fun navigateToWelcome()
    }

    private var callbacks: Callbacks? = null
    private var binding: FragmentWelcomeBinding? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding?.apply {
            loginButton.setOnClickListener { callbacks?.navigateToLogin() }
            signupButton.setOnClickListener { callbacks?.navigateToSignup() }
            uploadButton.setOnClickListener { callbacks?.navigateToUpload() }
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
