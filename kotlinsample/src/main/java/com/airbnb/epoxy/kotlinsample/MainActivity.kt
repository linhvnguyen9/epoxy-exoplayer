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
        recyclerView.adapter = controller.adapter
        recyclerView.setCacheManager(controller)

//        recyclerView.withModels {
//
//            for (i in 0 until 100) {
//                dataBindingItem {
//                    id("data binding $i")
//                    text("this is a data binding model")
//                    onClick { _ ->
//                        Toast.makeText(this@MainActivity, "clicked", Toast.LENGTH_LONG).show()
//                    }
//                    onVisibilityStateChanged { model, view, visibilityState ->
//                        Log.d(TAG, "$model -> $visibilityState")
//                    }
//                }
//
//                itemCustomView {
//                    id("custom view $i")
//                    color(Color.GREEN)
//                    title("this is a green custom view item")
//                    listener { _ ->
//                        Toast.makeText(this@MainActivity, "clicked", Toast.LENGTH_LONG).show()
//                    }
//                }
//
//                itemEpoxyHolder {
//                    id("view holder $i")
//                    title("this is a View Holder item")
//                    listener {
//                        Toast.makeText(this@MainActivity, "clicked", Toast.LENGTH_LONG)
//                            .show()
//                    }
//                }
//
//                postNewsFeed {
//                    id("postNewsFeed $i")
//                }
//
////                postNewsFeedModel {
////
////                }
//
//                carouselNoSnap {
//                    id("carousel $i")
//                    models(mutableListOf<CarouselItemCustomViewModel_>().apply {
//                        val lastPage = 10
//                        for (j in 0 until lastPage) {
//                            add(
//                                CarouselItemCustomViewModel_()
//                                    .id("carousel $i-$j")
//                                    .title("Page $j / $lastPage")
//                            )
//                        }
//                    })
//                }
//
//                // Since data classes do not use code generation, there's no extension generated here
//                ItemDataClass("this is a Data Class Item")
//                    .id("data class $i")
//                    .addTo(this)
//
//            }
//        }

        recyclerView.setPlayerSelector(selector)

    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
