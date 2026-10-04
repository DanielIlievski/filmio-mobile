package com.example.filmio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.filmio.feature.catalog.presentation.movie_list.MovieListRoot
import com.example.filmio.ui.theme.FilmioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FilmioTheme {
                MovieListRoot()
            }
        }
    }
}
