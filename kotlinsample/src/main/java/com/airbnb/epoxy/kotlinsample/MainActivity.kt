package com.airbnb.epoxy.kotlinsample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.epoxy.EpoxyVisibilityTracker
import com.airbnb.epoxy.kotlinsample.controller.NewsFeedController
import com.airbnb.epoxy.toro.PlayerSelector
import com.airbnb.epoxy.widget.ToroEpoxyCarousel

class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: ToroEpoxyCarousel

    internal var selector = PlayerSelector.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity)

        recyclerView = findViewById(R.id.recycler_view)

        // Attach the visibility tracker to the RecyclerView. This will enable visibility events.
        val epoxyVisibilityTracker = EpoxyVisibilityTracker()
        epoxyVisibilityTracker.attach(recyclerView)

        var dataSet = mutableListOf<Any>()

        for (i in 0 until 100) {
            dataSet.add(i)
        }

        val controller = NewsFeedController()

        controller.setData(dataSet)
        recyclerView.setNumViewsToShowOnScreen(1F)
        recyclerView.adapter = controller.adapter
        recyclerView.setCacheManager(controller)
        recyclerView.setPlayerSelector(selector)

    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
