# ExoPlayer_VS #

Branch: 360dash

This project is to enable server-side format selection of DASH.  


Features so far: 
* play 360 video with only finger control 
* enable DASH for H.264
* enable VR DASH
* enable H.265 MKV 
* enable local exoplayer to replace com.google..
* change in DefaultTrackSelector.java by replacing AdaptiveTrackSelection with MyTrackSelection 
* MyTrackSelector doesn't work with MyTrackSelection yet
* change default buffer value for DASH
* enable puffer for http streaming (okhttp) but only video works
