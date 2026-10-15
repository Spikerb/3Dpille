// Hands the Xaman result (query string) over to the Android app "Objem hromady".
(function () {
  var path = document.body.getAttribute('data-path');           // "auth" or "signed"
  var query = window.location.search || '';
  var custom = 'stockpilevolume://xaman/' + path + query;
  var intent = 'intent://xaman/' + path + query +
    '#Intent;scheme=stockpilevolume;package=com.stockpile.volume;end';
  var isAndroid = /Android/i.test(navigator.userAgent);
  var link = document.getElementById('open');
  link.href = isAndroid ? intent : custom;
  // Try automatically; if the browser blocks it, the button stays visible.
  setTimeout(function () { window.location.href = link.href; }, 150);
})();
