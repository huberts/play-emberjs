// DOM
var Element = {};
Element.firstChild = function () { return Element; };
Element.childNodes = [
  {nodeValue: ''}
];
Element.innerHTML = function () { return Element; };
Element.setAttribute = function (same, value) { return Element; };
Element.appendChild = function (el) { return Element; };

var document = { createRange: false, createElement: function() { return Element; } };
var window = this;
this.document = document;

// Console
var console = window.console = {};
console.log = console.info = console.warn = console.error = function(){};

// jQuery
var jQuery = window.jQuery = function() { return jQuery; };
jQuery.ready = function() { return jQuery; };
jQuery.inArray = function() { return jQuery; };
jQuery.jquery = "1.8.2";
jQuery.event = { fixHooks: {} };
var $ = jQuery;

// Ember
function precompileEmberHandlebars(string) {
  return Ember.Handlebars.precompile(string).toString();
}