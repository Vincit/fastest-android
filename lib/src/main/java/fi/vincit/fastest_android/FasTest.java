package fi.vincit.fastest_android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONArray;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

public class FasTest {
  private static final int DEFAULT_PORT = 7100;
  private static final String TAG = "FasTest";

  private static FasTest instance;
  private final Server server;

  public static void init(Application app, int port) {
    if (instance == null) {
      instance = new FasTest(app, port);
    }
  }

  public static void init(Application app) {
    init(app, DEFAULT_PORT);
  }

  private FasTest(Application app, int port) {
    TestContext ctx = new TestContext(app, new Handler(Looper.getMainLooper()));
    server = new Server(ctx, port);

    try {
      server.start();
    } catch (Throwable error) {
      throw new RuntimeException(error);
    }
  }




  private static class Server extends NanoHTTPD {
    static final String MIME_TYPE_JsonObject = "application/json";
    static final String HEADER_CONTENT_LENGTH = "content-length";

    final TestContext ctx;
    final List<RequestHandler> requestHandlers = new ArrayList<>();

    Server(TestContext ctx, int port) {
      super(port);

      this.ctx = ctx;
      requestHandlers.add(new CreateSessionRequestHandler(ctx));
      requestHandlers.add(new ImplicitWaitRequestHandler(ctx));
      requestHandlers.add(new GetWindowRectRequestHandler(ctx));
      requestHandlers.add(new GetElementsRequestHandler(ctx));
      requestHandlers.add(new ClickElementRequestHandler(ctx));
      requestHandlers.add(new IsElementDisplayedRequestHandler(ctx));
      requestHandlers.add(new IsElementEnabledRequestHandler(ctx));
      requestHandlers.add(new IsElementSelectedRequestHandler(ctx));
      requestHandlers.add(new SetElementValueRequestHandler(ctx));
      requestHandlers.add(new GetElementTextRequestHandler(ctx));
      requestHandlers.add(new GetElementRectRequestHandler(ctx));
      requestHandlers.add(new FlickRequestHandler(ctx));
      requestHandlers.add(new HideKeyboardRequestHandler(ctx));
    }

    @Override
    public Response serve(IHTTPSession req) {
      Response.Status status = Response.Status.OK;
      JsonObject body = readBody(req);
      JsonObject responseBody = JsonObject.newObject();

      Log.d(TAG, "request received: " + req.getMethod().name() + " " + req.getUri() + " " + body.toString());

      try {
        for (RequestHandler handler : requestHandlers) {
          if (handler.canHandle(req)) {
            responseBody = handler.handle(req, body);
            break;
          }
        }
      } catch (Throwable err) {
        Log.e(TAG, "request handling error", err);

        status = Response.Status.INTERNAL_ERROR;
        responseBody = JsonObject.newObject();
        responseBody.put("error", err.getMessage());
      }

      return jsonResponse(responseBody, status);
    }

    JsonObject readBody(IHTTPSession req) {
      String sizeStr = req.getHeaders().get(HEADER_CONTENT_LENGTH);
      int size = sizeStr == null ? 0 : Integer.valueOf(sizeStr);

      try {
        if (size > 0) {
          byte[] bytes = new byte[size];
          req.getInputStream().read(bytes);
          return new JsonObject(new String(bytes, "UTF-8"));
        } else {
          return JsonObject.newObject();
        }
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    Response jsonResponse(JsonObject body, Response.Status status) {
      String bodyStr = body.toString();
      Log.d(TAG, "sending response: " + bodyStr);
      return newFixedLengthResponse(status, MIME_TYPE_JsonObject, bodyStr);
    }
  }




  private static class TestContext {
    final Context context;
    final Handler handler;
    final ViewFinder viewFinder;
    final WeakHashMap<View, String> viewCache = new WeakHashMap<>();

    long timeout = 10000L;
    long pollInterval = 100L;

    TestContext(Application app, Handler handler) {
      this.context = app;
      this.handler = handler;
      this.viewFinder = new ViewFinder(app);
    }

    View findView(String id) {
      for (Map.Entry<View, String> entry : viewCache.entrySet()) {
        if (entry.getValue().equals(id)) {
          return entry.getKey();
        }
      }

      return null;
    }
  }




  private interface Func<V, R> {
    R run(V value);
  }




  private interface Callback<T> {
    void done(Throwable error, T result);
  }




  private static class RequestHandlerResult {
    JsonObject result;
    Throwable error;
  }




  private static abstract class RequestHandler {
    protected final TestContext ctx;

    RequestHandler(TestContext ctx) {
      this.ctx = ctx;
    }

    abstract boolean canHandle(NanoHTTPD.IHTTPSession req);
    abstract void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, Callback<JsonObject> callback);

    JsonObject handle(final NanoHTTPD.IHTTPSession req, final JsonObject body) throws Throwable {
      final CountDownLatch latch = new CountDownLatch(1);
      final RequestHandlerResult result = new RequestHandlerResult();

      ctx.handler.post(new Runnable() {
        @Override
        public void run() {
          try {
            handleInMainThread(req, body, new Callback<JsonObject>() {
              @Override
              public void done(Throwable error, JsonObject res) {
                result.result = res;
                result.error = error;
                latch.countDown();
              }
            });
          } catch (Throwable err) {
            result.result = null;
            result.error = err;
            latch.countDown();
          }
        }
      });

      // Wait here until the work is done in the main thread.
      latch.await();

      if (result.error != null) {
        throw result.error;
      }

      return result.result;
    }
  }




  private static abstract class ElementRequestHandler extends RequestHandler {

    ElementRequestHandler(TestContext ctx) {
      super(ctx);
    }

    View findElementView(NanoHTTPD.IHTTPSession req) {
      String[] uriParts = req.getUri().split("/");
      String id = uriParts[uriParts.length - 2];
      return ctx.findView(id);
    }
  }




  private static class CreateSessionRequestHandler extends RequestHandler {

    CreateSessionRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.POST && req.getUri().endsWith("session");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, Callback<JsonObject> callback) {
      JsonObject result = JsonObject.newObject();
      result.put("sessionId", UUID.randomUUID().toString());
      callback.done(null, result);
    }
  }




  private static class ImplicitWaitRequestHandler extends RequestHandler {

    ImplicitWaitRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.POST && req.getUri().endsWith("implicit_wait");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, Callback<JsonObject> callback) {
      ctx.timeout = body.getLong("ms");
      callback.done(null, JsonObject.newObject());
    }
  }




  private static class GetWindowRectRequestHandler extends RequestHandler {

    GetWindowRectRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.GET && req.getUri().endsWith("window/rect");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, final Callback<JsonObject> callback) {
      JsonObject result = JsonObject.newObject();
      JsonObject rectJson = JsonObject.newObject();
      Rect rect = ViewUtils.getWindowRect(ctx.viewFinder.getRootView());
      rectJson.put("x", rect.left);
      rectJson.put("y", rect.top);
      rectJson.put("width", rect.width());
      rectJson.put("height", rect.height());
      result.put("value", rectJson);
      callback.done(null, result);
    }
  }




  private static class GetElementsRequestHandler extends RequestHandler {

    GetElementsRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.POST && req.getUri().endsWith("elements");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, final Callback<JsonObject> callback) {
      final String strategy = body.getString("using");
      final String value = body.getString("value");
      final Func<View, Boolean> tester;

      if ("xpath".equals(strategy)) {
        tester = createXPathTester(value);
      } else if ("class name".equals(strategy)) {
        tester = createClassNameTester(value);
      } else {
        tester = createIdTester(value);
      }

      AsyncUtils.poll(ctx.handler, ctx.pollInterval, ctx.timeout, new Func<Void, List<View>>() {
        @Override
        public List<View> run(Void value) {
          final Rect windowRect = ViewUtils.getWindowRect(ctx.viewFinder.getRootView());

          final List<View> views = ctx.viewFinder.findViews(new Func<View, Boolean>() {
            @Override
            public Boolean run(View view) {
              return tester.run(view) && ViewUtils.isVisible(view, windowRect);
            }
          });

          if (views.isEmpty()) {
            return null;
          } else {
            return views;
          }
        }
      }, new Callback<List<View>>() {
        @Override
        public void done(Throwable error, List<View> views) {
          if (views == null) {
            views = new ArrayList<>();
          }

          if (error != null) {
            callback.done(error, null);
          } else {
            JsonObject elements = JsonObject.newArray();
            JsonObject result = JsonObject.newObject();

            for (View view : views) {
              JsonObject element = JsonObject.newObject();
              String id = ctx.viewCache.get(view);

              if (id == null) {
                id = "element-" + UUID.randomUUID().toString();
                ctx.viewCache.put(view, id);
              }

              element.put("ELEMENT", id);
              elements.add(element);
            }

            result.put("value", elements);
            callback.done(null, result);
          }
        }
      });
    }

    Func<View, Boolean> createXPathTester(String xpath) {
      try {
        final Pattern pattern = Pattern.compile("//(.+)\\[@text=['\"](.+)['\"]\\]");
        final Matcher matcher = pattern.matcher(xpath);

        if (matcher.find()) {
          final String className = matcher.group(1);
          final String text = matcher.group(2);
          final Class<?> viewClass = Class.forName(className);

          return new Func<View, Boolean>() {
            @Override
            public Boolean run(View view) {
              return viewClass.isAssignableFrom(view.getClass())
                && view instanceof TextView
                && text.equals(((TextView) view).getText().toString());
            }
          };
        } else {
          throw new RuntimeException("invalid or unsupported xpath " + xpath);
        }
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    Func<View, Boolean> createClassNameTester(String className) {
      try {
        final Class<?> viewClass = Class.forName(className);

        return new Func<View, Boolean>() {
          @Override
          public Boolean run(View view) {
            return viewClass.isAssignableFrom(view.getClass());
          }
        };
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    Func<View, Boolean> createIdTester(String packageAndId) {
      final String[] parts = packageAndId.split(":");
      final String defPackage = parts[0];
      final String idStr = parts[1].split("/")[1];
      final int id = ctx.context.getResources().getIdentifier(idStr, "id", defPackage);

      return new Func<View, Boolean>() {
        @Override
        public Boolean run(View view) {
          return view.getId() == id;
        }
      };
    }
  }




  private static class ClickElementRequestHandler extends ElementRequestHandler {

    ClickElementRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.POST && req.getUri().endsWith("click");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, final Callback<JsonObject> callback) {
      final View view = findElementView(req);
      final Rect rect = ViewUtils.getViewRectInWindow(view);
      final long downTime = SystemClock.uptimeMillis();

      ViewUtils.dispatchTouchEvent(
        view,
        MotionEvent.ACTION_DOWN,
        downTime,
        downTime,
        rect.centerX(),
        rect.centerY()
      );

      ctx.handler.postDelayed(new Runnable() {
        @Override
        public void run() {
          final Rect rect = ViewUtils.getViewRectInWindow(view);

          ViewUtils.dispatchTouchEvent(
            view,
            MotionEvent.ACTION_UP,
            downTime,
            SystemClock.uptimeMillis(),
            rect.centerX(),
            rect.centerY()
          );

          callback.done(null, JsonObject.newObject());
        }
      }, 50);
    }
  }




  private static class IsElementDisplayedRequestHandler extends ElementRequestHandler {

    IsElementDisplayedRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.GET && req.getUri().endsWith("displayed");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, final Callback<JsonObject> callback) {
      View view = findElementView(req);
      JsonObject result = JsonObject.newObject();
      result.put("value", ViewUtils.isVisible(view));
      callback.done(null, result);
    }
  }




  private static class IsElementEnabledRequestHandler extends ElementRequestHandler {

    IsElementEnabledRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.GET && req.getUri().endsWith("enabled");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, final Callback<JsonObject> callback) {
      View view = findElementView(req);
      JsonObject result = JsonObject.newObject();
      result.put("value", view.isEnabled());
      callback.done(null, result);
    }
  }




  private static class IsElementSelectedRequestHandler extends ElementRequestHandler {

    IsElementSelectedRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.GET && req.getUri().endsWith("selected");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, final Callback<JsonObject> callback) {
      View view = findElementView(req);
      JsonObject result = JsonObject.newObject();

      if (view instanceof CompoundButton) {
        CompoundButton button = (CompoundButton) view;
        result.put("value", button.isChecked());
      } else {
        result.put("value", view.isSelected());
      }

      callback.done(null, result);
    }
  }




  private static class SetElementValueRequestHandler extends ElementRequestHandler {

    SetElementValueRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.POST && req.getUri().endsWith("value");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, Callback<JsonObject> callback) {
      View view = findElementView(req);
      JsonObject value = body.getJsonArray("value");
      String text = "";

      for (int i = 0; i < value.size(); ++i) {
        text += value.getString(i);
      }

      ((EditText) view).setText(text);
      callback.done(null, JsonObject.newObject());
    }
  }




  private static class GetElementTextRequestHandler extends ElementRequestHandler {

    GetElementTextRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.GET && req.getUri().endsWith("text");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, Callback<JsonObject> callback) {
      View view = findElementView(req);
      TextView textView = (TextView) view;
      JsonObject result = JsonObject.newObject();

      if (textView.getText() != null) {
        result.put("value", textView.getText().toString());
      } else {
        result.put("value", (String) null);
      }

      callback.done(null, result);
    }
  }




  private static class GetElementRectRequestHandler extends ElementRequestHandler {

    GetElementRectRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.GET && req.getUri().endsWith("rect");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, Callback<JsonObject> callback) {
      View view = findElementView(req);
      Rect rect = ViewUtils.getViewRectInWindow(view);

      JsonObject result = JsonObject.newObject();
      JsonObject rectJson = JsonObject.newObject();

      rectJson.put("x", rect.left);
      rectJson.put("y", rect.top);
      rectJson.put("width", rect.width());
      rectJson.put("height", rect.height());

      result.put("value", rectJson);
      callback.done(null, result);
    }
  }




  private static class FlickRequestHandler extends ElementRequestHandler {
    private final static int STEPS = 50;

    FlickRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.POST && req.getUri().endsWith("flick");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, final Callback<JsonObject> callback) {
      final View view = ctx.findView(body.getString("element"));
      final Rect rect = ViewUtils.getViewRectInWindow(view);

      final double xOffset = body.getDouble("xoffset");
      final double yOffset = body.getDouble("yoffset");
      final double speed = body.getDouble("speed");

      final double distance = Math.sqrt(xOffset * xOffset + yOffset * yOffset);
      final double durationSeconds = distance / speed;
      final double intervalSeconds = durationSeconds / STEPS;
      final long intervalMillis = Math.round(intervalSeconds * 1000);

      AsyncUtils.poll(ctx.handler, intervalMillis, Integer.MAX_VALUE, new Func<Void, Boolean>() {
        private int step = -1;
        private long downTime;

        @Override
        public Boolean run(Void value) {
          final long time = SystemClock.uptimeMillis();
          ++step;

          if (step == 0) {
            downTime = time;

            ViewUtils.dispatchTouchEvent(
              view,
              MotionEvent.ACTION_DOWN,
              downTime,
              time,
              rect.centerX(),
              rect.centerY()
            );

            return null;
          } else if (step == STEPS) {
            ViewUtils.dispatchTouchEvent(
              view,
              MotionEvent.ACTION_UP,
              downTime,
              time,
              (float) (rect.centerX() + xOffset),
              (float) (rect.centerY() + yOffset)
            );

            return true;
          } else {
            final double fraction = step / (double) STEPS;

            ViewUtils.dispatchTouchEvent(
              view,
              MotionEvent.ACTION_MOVE,
              downTime,
              time,
              (float) (rect.centerX() + xOffset * fraction),
              (float) (rect.centerY() + yOffset * fraction)
            );

            return null;
          }
        }
      }, new Callback<Boolean>() {
        @Override
        public void done(Throwable error, Boolean result) {
          if (error != null) {
            callback.done(error, null);
          } else {
            callback.done(null, JsonObject.newObject());
          }
        }
      });
    }
  }




  private static class HideKeyboardRequestHandler extends RequestHandler {

    HideKeyboardRequestHandler(TestContext ctx) {
      super(ctx);
    }

    @Override
    boolean canHandle(NanoHTTPD.IHTTPSession req) {
      return req.getMethod() == NanoHTTPD.Method.POST && req.getUri().endsWith("hide_keyboard");
    }

    @Override
    void handleInMainThread(NanoHTTPD.IHTTPSession req, JsonObject body, final Callback<JsonObject> callback) {
      Activity activity = (Activity) ctx.viewFinder.getRootView().getContext();

      InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
      imm.hideSoftInputFromWindow(activity.getCurrentFocus().getWindowToken(), 0);

      // Wait until the keyboard is closed.
      ctx.handler.postDelayed(new Runnable() {
        @Override
        public void run() {
          callback.done(null, JsonObject.newObject());
        }
      }, 300);
    }
  }




  private static class JsonObject {
    private final JSONObject obj;
    private final JSONArray arr;

    static JsonObject newObject() {
      return new JsonObject(new JSONObject());
    }

    static JsonObject newArray() {
      return new JsonObject(new JSONArray());
    }

    JsonObject(JSONObject obj) {
      this.obj = obj;
      this.arr = null;
    }

    JsonObject(JSONArray arr) {
      this.obj = null;
      this.arr = arr;
    }

    JsonObject(String json) {
      try {
        this.obj = new JSONObject(json);
        this.arr = null;
      } catch (Throwable err) {
        throw new RuntimeException(err);
      }
    }

    void put(String name, String value) {
      try {
        obj.put(name, value);
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    void put(String name, double value) {
      try {
        obj.put(name, value);
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    void put(String name, boolean value) {
      try {
        obj.put(name, value);
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    void put(String name, JsonObject value) {
      try {
        if (value.arr != null) {
          obj.put(name, value.arr);
        } else {
          obj.put(name, value.obj);
        }
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    String getString(String name) {
      try {
        return obj.getString(name);
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    long getLong(String name) {
      try {
        return obj.getLong(name);
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    double getDouble(String name) {
      try {
        return obj.getDouble(name);
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    JsonObject getJsonArray(String name) {
      try {
        return new JsonObject(obj.getJSONArray(name));
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    int size() {
      try {
        return arr.length();
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    String getString(int i) {
      try {
        return arr.getString(i);
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    void add(JsonObject obj) {
      try {
        arr.put(arr.length(), obj.obj);
      } catch (Throwable error) {
        throw new RuntimeException(error);
      }
    }

    @Override
    public String toString() {
      if (obj != null) {
        return obj.toString();
      } else {
        return arr.toString();
      }
    }
  }




  @SuppressWarnings("unchecked")
  private static class ViewFinder {
    private Activity currentActivity;

    ViewFinder(Application app) {
      app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
        @Override public void onActivityDestroyed(Activity activity) {}

        @Override
        public void onActivityResumed(Activity activity) {
          currentActivity = activity;
        }
      });
    }

    List<View> findViews(Func<View, Boolean> test) {
      return findViews(getRootView(), test, new ArrayList<View>());
    }

    List<View> findViews(View view, Func<View, Boolean>  test, List<View> foundViews) {
      if (test.run(view)) {
        foundViews.add(view);
      }

      if (view instanceof ViewGroup) {
        ViewGroup viewGroup = (ViewGroup) view;

        for (int i = 0; i < viewGroup.getChildCount(); ++i) {
          findViews(viewGroup.getChildAt(i), test, foundViews);
        }
      }

      return foundViews;
    }

    View getRootView() {
      List<View> rootViews = getRootViews();

      // Sometimes the views are not in correct order in the `rootViews` list. Sometimes
      // the resumed activity is not the last item in the list. Since We keep track of the
      // resumed activity and we can here check that.
      for (int i = rootViews.size() - 1; i >= 0; --i) {
        View rootView = rootViews.get(i);

        if (!(rootView.getContext() instanceof Activity) || rootView.getContext() == currentActivity) {
          return rootView;
        }
      }

      return rootViews.get(rootViews.size() - 1);
    }

    static List<View> getRootViews() {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH &&
          Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {

          Class wmiClass = Class.forName("android.view.WindowManagerImpl");
          Object wmiInstance = wmiClass.getMethod("getDefault").invoke(null);

          return viewsFromWM(wmiClass, wmiInstance);

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {

          Class wmgClass = Class.forName("android.view.WindowManagerGlobal");
          Object wmgInstance = wmgClass.getMethod("getInstance").invoke(null);

          return viewsFromWM(wmgClass, wmgInstance);
        }

      } catch (Throwable error) {
        throw new RuntimeException(error);
      }

      return new ArrayList<>();
    }

    static List<View> viewsFromWM(Class wmClass, Object wmInstance) throws Exception {
      Field viewsField = wmClass.getDeclaredField("mViews");
      viewsField.setAccessible(true);
      Object views = viewsField.get(wmInstance);

      if (views instanceof List) {
        return (List<View>) viewsField.get(wmInstance);
      } else if (views instanceof View[]) {
        return Arrays.asList((View[]) viewsField.get(wmInstance));
      }

      return new ArrayList<>();
    }
  }




  private static class ViewUtils {

    static void dispatchTouchEvent(View view,
                                   int action,
                                   long downTime,
                                   long time,
                                   float x,
                                   float y) {

      final View rootView = view.getRootView();
      final Context rootViewContext = rootView.getContext();

      MotionEvent event = MotionEvent.obtain(
        downTime,
        time,
        action,
        x,
        y,
        0
      );

      if (rootViewContext instanceof Activity) {
        ((Activity) rootViewContext).dispatchTouchEvent(event);
      } else {
        rootView.dispatchTouchEvent(event);
      }

      event.recycle();
    }

    static boolean isVisible(View view) {
      return isVisible(view, getWindowRect(view));
    }

    static boolean isVisible(View view, Rect windowRect) {
      final Rect rect = getViewRectInWindow(view);
      return isShown(view) && windowRect.intersects(rect.left, rect.top, rect.right, rect.bottom);
    }

    static boolean isShown(View view) {
      View parent = view;

      while (parent != null) {
        if (parent.getVisibility() != View.VISIBLE) {
          return false;
        }

        if (parent.getParent() == null || !(parent.getParent() instanceof View)) {
          break;
        }

        parent = (View) parent.getParent();
      }

      return true;
    }

    static Rect getViewRectInWindow(View view) {
      final int[] loc = new int[2];

      view.getLocationInWindow(loc);

      return new Rect(loc[0], loc[1], loc[0] + view.getWidth(), loc[1] + view.getHeight());
    }

    static Rect getWindowRect(View view) {
      final WindowManager wm = (WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE);

      final Display display = wm.getDefaultDisplay();
      final DisplayMetrics metrics = new DisplayMetrics();
      display.getMetrics(metrics);

      return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }
  }




  private static class AsyncUtils {

    static <R> void poll(final Handler handler,
                         final long interval,
                         final long timeout,
                         final Func<Void, R> test,
                         final Callback<R> callback) {

      final long startTime = System.currentTimeMillis();

      final Runnable runnable = new Runnable() {
        @Override
        public void run() {
          final long time = System.currentTimeMillis();
          R result;

          try {
            result = test.run(null);
          } catch (Throwable error) {
            callback.done(error, null);
            return;
          }

          if (time - startTime >= timeout) {
            callback.done(null, result);
            return;
          }

          if (result == null) {
            handler.postDelayed(this, interval);
          } else {
            callback.done(null, result);
          }
        }
      };

      runnable.run();
    }
  }
}


