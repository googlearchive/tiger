// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////
package sample.child;

import javax.inject.Inject;
import sample.Bar;
import sample.BarAnnotation;
import sample.Foo;

public class Shboom {

    @Inject
    Foo foo;
    
    private Bar bar;
    
    @Inject
    void injectBar(@BarAnnotation Bar bar) {
      this.bar = bar; 
    }
    
    @Inject
    public Shboom() {      
    }
    
    @Override
    public String toString() {
      return "Shboom[foo: " + foo + " bar: " + bar + "]";
    }
}
