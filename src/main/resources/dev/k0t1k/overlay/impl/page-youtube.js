// Best effort against YouTube video ads that the page's own data pruning missed: while the player
// shows an ad, mute it, jump to the end and press the skip button when there is one.
(function () {
  if (window.__k0t1kYoutube) return;
  window.__k0t1kYoutube = 1;
  var active = false, wasMuted = false;
  setInterval(function () {
    var player = document.querySelector('#movie_player.ad-showing, .html5-video-player.ad-showing');
    var video = document.querySelector('video.html5-main-video') || document.querySelector('video');
    if (!video) return;
    if (!player) {
      if (active) { active = false; video.muted = wasMuted; }
      return;
    }
    if (!active) { active = true; wasMuted = video.muted; }
    video.muted = true;
    if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.1) {
      video.currentTime = video.duration;
    }
    var skip = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
    if (skip) skip.click();
    var close = document.querySelector('.ytp-ad-overlay-close-button');
    if (close) close.click();
  }, 400);
})();
