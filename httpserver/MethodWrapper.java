package httpserver;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A MethodWrapper is a wrapper for the {@link java.lang.reflect.Method} class.
 * It allows us to easily invoke a method based on a path without worrying
 * about parsing out the variables and whatnot.
 *
 * MethodWrapper isn't visible to the outside world, because it shouldn't be
 * used outside of this httpserver. This documentation exists to help people
 * better understand and modify the underlying server.
 */
class MethodWrapper {
  private static final String LANG_PATH = "java.lang.";

  private String path;
  private Method method;


  /**
   * Create a MethodWrapper.
   *
   * Paths should come in <code>/relative/path/to/match</code>. To use a
   * variable in a path, it should come in
   * <code>/path/with/{VariableType}</code> form, where VariableType is a
   * class inside of the `java.lang` package, and has a constructor that takes
   * in a String. A better explaination is in {@link HTTPHandler#addGET}
   *
   * @param path          Path the be matched for this method to be used.
   * @param methodName    Name of the method to be called.
   * @param callingClass  Class the method belongs to.
   *
   * @throws HTTPException  If there is no callingClass.methodName method. If
   *                        the wrong number of variable parameters are used in
   *                        the path. If the variable parameters are in the
   *                        wrong order in the path.
   *
   * @see HTTPHandler#addGET
   */
  public MethodWrapper(String path, String methodName, Class callingClass)
          throws HTTPException {
    try {
      // Get a list of the parameter types
      List<Class> parameterTypes = new ArrayList<Class>();
      String[] paths = path.split("/");
      StringBuilder pathBuilder = new StringBuilder();

      /*  Recreate the path.
          This is done so that a path may include or exclude a `/` at the end
          of it. It also makes sure that non-dynamic parts of the path are
          lower case'd.
       */
      for (int i=0; i<paths.length; i++) {
        /*  if, for some reason, there's something like a `//` in the path,
            or if it's the first one (because of the proceeding /), part is
            empty, which means we have nothing to do here.
         */
        if (paths[i].isEmpty()) {
          continue;
        }

        if (isDynamic(paths[i])) {
          String paramClass = null;

          if(!isArray(paths[i])) {
            paramClass = LANG_PATH + getParamType(paths[i]);
          }
          // If we have an array, we have to handle it slightly different.
          else {
            // Make sure they are not trying to add an array in the middle of
            // the parameter's list.
            if(i != paths.length - 1)
              throw new HTTPException("An array must be the last parameter.");

            String arrayType = LANG_PATH + getParamType(paths[i].substring(0, paths[i].indexOf('.')) + "}");

            // This is how an array is represented in reflection.
            paramClass = "[L" + arrayType + ";";
          }

          parameterTypes.add(Class.forName(paramClass));
          //paths[i] = "{" + paths[i] + "}"; // TODO: this is a dirty hack. Fix it.
        }
        else {
          paths[i].toLowerCase();
        }

        pathBuilder.append('/');
        pathBuilder.append(paths[i]);
      }


      this.path = pathBuilder.toString();

      // If the path was just a '/' it will be empty
      if (this.path.isEmpty()) {
        this.path = "/";
      }

      /*  Because Class.getMethod() takes in an array of Classes, and because
          List.toArray() returns an array of Objects, we need to manually
          convert parameterTypes from a list to an array.
       */
      Class[] paramTypes = new Class[parameterTypes.size()];
      for (int i=0; i < parameterTypes.size(); i++) {
        paramTypes[i] = parameterTypes.get(i);
      }

      method = callingClass.getMethod(methodName, paramTypes);
    }
    catch(ClassNotFoundException | NoSuchMethodException
            | SecurityException e) {
      throw new HTTPException("Could not add path.", e);
    }
  }


  /**
   * Invoke the method.
   *
   * @param callingClass  The class the method belongs to.
   * @param path          The path that caused the method to be called. This is
   *                      where variables come from.
   *
   * @throws HTTPException  If anything bad happened in invoking the underlying
   *                        method. Probably shouldn't happen, because the
   *                        issues would be found first when making the
   *                        MethodWrapper, but there's a chance they could
   *                        happen.
   *
   * @see java.lang.reflect.Method#invoke
   */
  public void invoke(Object callingClass, String path) throws HTTPException {
    try {
      // Get the parameters
      String[] paths = path.split("/");
      String[] methodPaths = this.path.split("/");
      List<Object> params = new ArrayList<Object>();

      for (int i = 0; i < paths.length; i++) {
        if (isDynamic(methodPaths[i])) {
          if (!isArray(methodPaths[i])) {
            Class paramClass = Class.forName(LANG_PATH
                    + getParamType(methodPaths[i]));

            Constructor paramConstructor
            = paramClass.getConstructor(String.class);

            params.add(paramConstructor.newInstance(paths[i]));
          }
          // If we have an array.
          else {
            // Figure out the class type / constructor.
            Class paramClass =
                    Class.forName(LANG_PATH + getParamType(methodPaths[i]));
            Constructor paramConstructor =
                    paramClass.getConstructor(String.class);

            // Create an arraylist so we can have variable sizes.
            ArrayList<Object> list = new ArrayList<>();

            // Place the values in our list.
            for(int j=i; j<paths.length; j++) {
              list.add(paramConstructor.newInstance(paths[j]));
            }

            // Use reflection to create a list of our objects at a set size.
            Object[] listObj = (Object[]) Array.newInstance(paramClass, list.size());
            for(int j=0; j<list.size(); j++){
              listObj[j] = list.get(j);
            }

            // Add our full list to our parameters array.
            params.add(listObj);

            // Break out because we've been through all of the `methodPaths`.
            break;
          }
        }
      }

      // Method.invoke throws an exception if an empty array is passed in
      if (params.isEmpty()) {
        method.invoke(callingClass);
      }
      else {
        method.invoke(callingClass, params.toArray());
      }
    }
    catch (IllegalAccessException | IllegalArgumentException
            | InvocationTargetException | SecurityException
            | ClassNotFoundException | NoSuchMethodException
            | InstantiationException e) {
      throw new HTTPException("Could not invoke method `" + method + "`.", e);
    }
  }


  /**
   * Determines how correct a method is from a path. The higher the number,
   * the more likely the method is the correct method.
   *
   * Correctness is based on the similarity of the passed in path, and the
   * method's path. If a path segment (the part between two slashes) matches
   * this method's corresponding segment exactly, the correctness number is
   * incremented by three. If the segment matches the variable type of the
   * corresponding segment, the correctness number is incremented by one, and
   * if the variable class can contain a decimal in it, the correctness
   * number is incremented by one, again.
   *
   * If a zero is returned, the passed in path doesn't match this method's
   * path at all.
   *
   * @param path  The path, relative the the handler.
   *
   * @return  A "correctness number", based on how well the passed in
   *          path matches this method's path.
   */
  public int howCorrect(String path) {
    String[] paths = path.split("/");
    String[] methodPaths = this.path.split("/");

    // If the paths aren't the same length and it is not an array,
    // this is the wrong method.
    if (paths.length != methodPaths.length) {
      if(!isArray(this.path))
        return 0;
    }

    // Start count at 1 because of the length matching.
    int count = 1;
    for (int i = 0; i < paths.length && i < methodPaths.length; i++) {
      // If the paths are equal, give it priority over other methods.
      if (paths[i].equals(methodPaths[i])) {
        count += 3;
      }
      else if (isDynamic(methodPaths[i])) {
        // If we can create the object, we can count up
        if(!isArray(methodPaths[i])) {
          if(canInstantiate(getParamType(methodPaths[i]), paths[i])) {
            count++;

            // Give priority to non-decimal types.
            if (!hasDecimal(methodPaths[i])) {
              count++;
            }
          }
        }
        // If we can't create the object, check if it's an array.
        else {
          String paramType = getParamType(methodPaths[i]);

          // Give priority to non-decimal types.
          if(!hasDecimal(methodPaths[i]))
            count++;

          for(int j=i; j<paths.length; j++) {
            if(canInstantiate(paramType, paths[j])) {
              count++;
            }
            // If we can't instantiate part of the path, we don't have a match.
            else {
              return 0;
            }
          }
        }
      }
    }
    return count;
  }


  /**
   * Checks to see if we can instantiate an object of a class type,
   * given a `String`.
   * @param classType The class type to instantiate.
   * @param value The string value.
   * @return
   */
  private boolean canInstantiate(String classType, String value) {
    try {
      Class paramClass = Class.forName(LANG_PATH + classType);
      Constructor constructor = paramClass.getConstructor(String.class);

      constructor.newInstance(value);
    } catch (NoSuchMethodException | SecurityException | ClassNotFoundException
            | InstantiationException | IllegalAccessException
            | IllegalArgumentException | InvocationTargetException e) {
      // If anything bad happens, we can't instantiate.
      return false;
    }

    // If we got through everything, we're good.
    return true;
  }

  /**
   * Checks if a class allows a decimal or not
   *
   * @param paramClass  Class being checked
   * @return  If the class is a BigDecimal, Double, or Float.
   */
  private boolean hasDecimal(String path) {
    try {
      Class paramClass = Class.forName(LANG_PATH
              + getParamType(path));

      return paramClass.equals(BigDecimal.class)
              || paramClass.equals(Double.class)
              || paramClass.equals(Float.class);
    } catch (ClassNotFoundException e) {
      return false;
    }

  }


  // TODO Clean up regex.
  /**
   * Checks if there is dynamic text in part of a path.
   *
   * @param path  Part of the path you want to check for dynamic data.
   * @return  If the path matches the regex pattern `\{[A-Za-z0-9]{1,}\}`
   */
  private boolean isDynamic(String path) {
    //         Normal dynamic: `{Integer}`
    return path.matches("\\{[A-Za-z0-9]{1,}\\}")
            // Dynamic with variable name: `{Integer name}`
            || path.matches("\\{[A-Za-z0-9]{1,} [A-Za-z0-9-_]{1,}\\}")
            // Dynamic with variable name: `{Integer} name`
            || path.matches("\\{[A-Za-z0-9]{1,}\\} [A-Za-z0-9-_]{1,}")
            // Array `{Integer... name} || `{Integer...}` || `{Integer ... name}` etc.
            || path.matches("\\{[A-Za-z0-9]{1,} {0,}[.]{3} {1,}[A-Za-z0-9]{0,}\\}")
            // Array `{Integer...} name || `{Integer...}` || `{Integer ...} name` etc.
            || path.matches("\\{[A-Za-z0-9]{1,} {0,}[.]{3}\\} {0,}[A-Za-z0-9]{0,}");
  }

  /**
   * Gets the name of the Method
   *
   * @return the Method's name
   */
  public String getName() {
    return method.getName();
  }

  @Override
  public String toString() {
    return method.toString();
  }

  public String getParamType(String part) {
    // If the part is an array, it will have to start it after the "{" and
    // before the "..."
    if(isArray(part)) {
      if(part.contains(" ..."))
        return part.substring(1, part.indexOf(" ..."));
      else
        return part.substring(1, part.indexOf("..."));
    }

    // strip off anything after the `}`
    if (part.indexOf('}') < part.length())
      part = part.substring(0, part.indexOf('}') + 1);

    part = part.substring(1, part.length() - 1).split(" ")[0];
    return part;
  }

  /**
   * Checks if the type in a method's path is an array
   * @param path a part of the path of the method's paths.
   * @return if it is an array
   */
  public boolean isArray(String path) {
    return path.contains("...");
  }

}
