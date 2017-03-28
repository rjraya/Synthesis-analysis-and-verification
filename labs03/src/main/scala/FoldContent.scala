import inox._
import inox.trees.{forall => _, _}
import inox.trees.dsl._

import welder._

object FoldContent {

  val foldID = FreshIdentifier("fold")
  val contentID = FreshIdentifier("content")
  val withoutID = FreshIdentifier("without")
  val sizeID = FreshIdentifier("size")

  val list = FreshIdentifier("List")
  val cons = FreshIdentifier("Cons")
  val nil = FreshIdentifier("Nil")

  val head = FreshIdentifier("head")
  val tail = FreshIdentifier("tail")

  val listADT = mkSort(list)("A")(Seq(cons, nil))
  val consADT = mkConstructor(cons)("A")(Some(list)) { case Seq(aT) =>
    Seq(ValDef(head, aT), ValDef(tail, T(list)(aT)))
  }
  val nilADT = mkConstructor(nil)("A")(Some(list))(_ => Nil)
  /*
   * Definition of fold left
   *
   * def foldl[A](f: (A,A) => A, z: A, l: List[A]): A = l match {
   *   case Cons(x, xs) => foldl(f, f(z, x), xs)
   *   case Nil() => z
   * }
   */ 
  val foldFunction = mkFunDef(foldID)("A") { case Seq(aT) => (
    Seq("f" :: ((aT, aT) =>: aT), "z" :: aT, "l" :: T(list)(aT)), aT, { case Seq(f, z, l) =>
      if_ (l.isInstOf(T(cons)(aT))) {
        E(foldID)(aT)(f, f(z, l.asInstOf(T(cons)(aT)).getField(head)), l.asInstOf(T(cons)(aT)).getField(tail))
        } else_ {
          z
        }
    })
  }

  /* Content function as defined in the previous exercises in Stainless 
   *
   * def content: Set[BigInt] = this match {
   *   case Nil() => Set()
   *   case Cons(h, t) => Set(h) ++ t.content
   * }
   *
  */
  val contentFunction = mkFunDef(contentID)("A") { case Seq(aT) => 
    val args: Seq[ValDef] = Seq("l" :: T(list)(aT))
    val retType: Type = SetType(aT)
    val body: Seq[Variable] => Expr = { case Seq(l) =>
      if_ (l.isInstOf(T(cons)(aT))) {
        SetAdd(E(contentID)(aT)(l.asInstOf(T(cons)(aT)).getField(tail)), l.asInstOf(T(cons)(aT)).getField(head))
      } else_ {
        FiniteSet(Seq.empty, aT)
      }
    }
    (args, retType, body)
  }

  /*  Removes the occurrences of x from list A
   *
   *  def without(x: A, xs: List[A]) = xs match{
   *    case Nil() => Nil()
   *    case y :: ys if(x == y) => without(x,ys)
   *    case y :: ys if(x != y) => y :: without(x,ys)  
   *  }
   */ 
  val withoutFunction: FunDef = mkFunDef(withoutID)("A"){ case Seq(aT) => 
    val args: Seq[ValDef] = Seq("x" :: aT,"xs" :: T(list)(aT))
    val retType: Type = T(list)(aT)
    val body: Seq[Variable] => Expr = { case Seq(x,xs) => 
      if_ (xs.isInstOf(T(cons)(aT))) {
        val y = xs.asInstOf(T(cons)(aT)).getField(head)
        val ys = xs.asInstOf(T(cons)(aT)).getField(tail)
        if_ (x === y) {
          E(withoutID)(aT)(x,ys)
        } else_ {
          T(cons)(aT)( y, E(withoutID)(aT)(x,ys) )
        }
      } else_ {
        T(nil)(aT)()
      }
    }

    (args, retType, body)
  }


  val symbols = NoSymbols
    .withADTs(Seq(listADT, consADT, nilADT))
    .withFunctions(Seq(foldFunction, contentFunction, withoutFunction))

  val program = InoxProgram(Context.empty, symbols)

  val theory = theoryOf(program)
  import theory._

  val A = TypeParameter.fresh("A")

  def fold(f: Expr, z: Expr, l: Expr) = E(foldID)(A)(f, z, l)
  def content(e: Expr) = E(contentID)(A)(e)
  def without(x: Expr, xs: Expr) = E(withoutID)(A)(x,xs)
  def single(x: Expr) = FiniteSet(Seq(x),A)

  val ListA = T(list)(A)
  val ConsA = T(cons)(A)
  val NilA = T(nil)(A)

  /* This method generates a formula stating that the parameter function `f` is associative. */
  def isAssociative(f: Expr): Expr = forall("x" :: A, "y" :: A, "z" :: A)((x, y, z) => f(x, f(y, z)) === f(f(x, y), z))

  /* This method generates a formula stating that the parameter function `f` is commutative. */
  def isCommutative(f: Expr): Expr = forall("x" :: A, "y" :: A)((x, y) => f(x, y) === f(y, x))

  /* This method generates a formula stating that the parameter function `f` is idempotent. */
  def isIdempotent(f: Expr): Expr = forall("x" :: A, "y" :: A)((x, y) => f(x,y) === f(f(x, y),y))

  //x != y ==> content(xs).contains(x) === content(y::xs).contains(x)
  val lemma2: Theorem = prove(forall("x"::A,"y"::A,"xs"::ListA){case (x,y,xs) =>
    (x !== y) ==> {content(xs).contains(x) === content(ConsA(y,xs)).contains(x)}
  }) 

  //content(n1) === content(n2) ==> {content(n1).contains(x) === content(n2).contains(x)}
  val lemma4: Theorem = prove(forall("x" :: A,"n1" :: ListA, "n2" :: ListA){ case (x,n1,n2) =>
    (content(n1) === content(n2)) ==> { content(n1).contains(x) === content(n2).contains(x) }
  })

  def property5(l: Expr) =
    forall("x" :: A){ case x => 
      content(without(x,l)) === content(l) -- single(x)
    }

  val lemma5: Theorem = structuralInduction(property5 _, "l" :: ListA) { case (ihs, goal) =>
    forallI("x" :: A){ x =>
      ihs.expression match{
        case C(`cons`, y, ys) =>
          val eqLemma: Theorem = implI(x === y){ equality => 
            content(without(x,ConsA(y,ys)))                           ==|
                                                               equality |
            content(without(x,ConsA(x,ys)))                           ==|
                                                                trivial |
            content(without(x,ys))                                    ==|
                                                     ihs.hypothesis(ys) |
            content(ys) -- single(x)                                  ==|
                                                                trivial |
            content(ys).insert(x) -- single(x)                        ==|
                                                                trivial |
            content(ConsA(x,ys)) -- single(x)                         ==|
                                                               equality |
            content(ConsA(y,ys)) -- single(x)                    
          }       
          val neqLemma: Theorem = implI(x !== y){ nequality => 
            content(without(x,ConsA(y,ys)))                           ==|
                                                              nequality |
            content(ConsA(y,without(x,ys)))                           ==|
                                                                trivial |
            content(without(x,ys)).insert(y)                          ==|
                                                     ihs.hypothesis(ys) |
            (content(ys) -- single(x)).insert(y)                      ==| 
                                                              nequality |
            content(ys).insert(y) -- single(x)                        ==|
                                                                trivial |
            content(ConsA(y,ys)) -- single(x)                                    
          }
          andI(eqLemma,neqLemma)
        case C(`nil`) => truth
      }
    }
  }    

  val lemma6: Theorem = prove(forall("x" :: A, "l1"::ListA, "l2" :: ListA){ case (x,l1,l2) =>
    {content(l1) === content(l2)} ==> {content(without(x,l1)) === content(without(x,l2))}
    },lemma5)

  val lemma7: Theorem = prove(forall("x"::A, "xs"::ListA,"l2"::ListA){ case (x,xs,l2) => 
    {content(ConsA(x,xs)) === content(l2) && content(xs).contains(x)} ==> {content(xs) === content(l2)}
  })

  val lemma8: Theorem = prove(forall("x"::A, "xs"::ListA,"l2"::ListA){ case (x,xs,l2) => 
    {content(ConsA(x,xs)) === content(l2) && !content(xs).contains(x)} ==> 
      {content(xs) === content(l2) -- single(x)}
  })

  val lemma9: Theorem = prove(forall("x"::A,"xs"::ListA){ case (x,xs) => 
    content(ConsA(x,xs)).contains(x)
  })   

  /* 
   *    ∀f: (A,A) => A,z: A, l1: List[A], l2: List[A]. (
   *      isAssociative(f) &&
   *      isCommutative(f) &&
   *      isIdempotent(f) &&
   *      content(l1) == content(l2)
   *    ) ==> (fold(f, z, l1) == fold(f, z, l2))
   */
  val equivalent: Theorem = 
    forallI("f" :: ((A, A) =>: A), "z" :: A, "l1" :: ListA, "l2" :: ListA) { (f,z,l1,l2) => 
      implI(and(isAssociative(f),isCommutative(f), isIdempotent(f), content(l1) === content(l2))) { axioms => 
        val Seq(isAssocf, isCommutativef, isIdempotentf, equalContents) = andE(axioms) : Seq[Theorem]

        def property1(xs: Expr) = forall("x" :: A,"z" :: A){ case (x,z) =>
          fold(f,z,ConsA(x,xs)) === fold(f,z,ConsA(x,without(x,xs)))  
        }  
        val lemma1 = structuralInduction(property1 _,"xs" :: ListA) { case (ihs, goal) =>
            forallI("x" :: A,"z" :: A){ (x,z) => 

            ihs.expression match{
              case C(`cons`, y, ys) => 
                     
                val eqLemma: Theorem = implI(x === y){ equality => 
                  fold(f,z,ConsA(x,without(x, ihs.expression)))     ==| 
                                                             equality | 
                  fold(f,z,ConsA(x,without(x, ConsA(x,ys))))        ==|
                                                              trivial |
                  fold(f,z,ConsA(x,without(x,ys)))                  ==|
                                                   ihs.hypothesis(ys) | 
                  fold(f,z, ConsA(x,ys))                            ==|
                                                              trivial |
                  fold(f,f(z,x),ys)                                 ==|
                                          forallE(isIdempotentf)(z,x) |
                  fold(f, f(f(z,x),x),ys)                           ==|
                                                              trivial |
                  fold(f, f(z,x), ConsA(x,ys))                      ==|
                                                              trivial |
                  fold(f, z, ConsA(x, ConsA(x,ys)))                 ==|
                                                             equality |
                  fold(f, z, ConsA(y, ConsA(y,ys)))                 ==|
                                                              trivial |
                  fold(f,z,ConsA(y,ihs.expression))                                                                              
                }               
               
                val neqLemma: Theorem = implI(x !== y){ nequality =>
                  fold(f,z,ConsA(x,without(x,ConsA(y,ys))))         ==|
                                                            nequality |
                  fold(f,z,ConsA(x,ConsA(y,without(x,ys))))         ==|
                                                              trivial |
                  fold(f,f(z,x),ConsA(y,without(x,ys)))             ==|
                                                              trivial |
                  fold(f,f(f(z,x),y),without(x,ys))                 ==|
                                    forallE(isCommutativef)(f(z,x),y) |
                  fold(f,f(y,f(z,x)),without(x,ys))                 ==|
                                             forallE(isAssocf)(y,z,x) |
                  fold(f,f(f(y,z),x),without(x,ys))                 ==|  
                                                              trivial |
                  fold(f,f(y,z),ConsA(x,without(x,ys)))             ==|
                                forallE(ihs.hypothesis(ys))(x,f(y,z)) |
                  fold(f,f(y,z),ConsA(x,ys))                        ==|
                                                              trivial |
                  fold(f,f(f(y,z),x),ys)                            ==|
                                             forallE(isAssocf)(y,z,x) |
                  fold(f,f(y,f(z,x)),ys)                            ==|
                                    forallE(isCommutativef)(y,f(z,x)) |
                  fold(f,f(f(z,x),y),ys)                            ==|
                                                              trivial |   
                  fold(f,f(z,x),ConsA(y,ys))                        ==|
                                                              trivial |
                  fold(f,z,ConsA(x,ConsA(y,ys)))                   
                }

                andI(eqLemma, neqLemma)
             
              case C(`nil`) => truth
            }
          }
        }
        
        // contains(x,l2) ==> fold(f,z,l2) == fold(f,z,x::l2)
        def property3(xs: Expr) = forall("x" :: A,"z" :: A){ case (x,z) =>
          content(xs).contains(x) ==> { fold(f,z,xs) === fold(f,z,ConsA(x,xs)) }
        }  

        val lemma3: Theorem = structuralInduction(property3 _,"xs" :: ListA) { case (ihs, goal) =>
          forallI("x" :: A,"z" :: A){ (x,z) => 
            implI(content(ihs.expression).contains(x)){ containsx =>
              ihs.expression match{
                case C(`cons`, y, ys) =>
                  // contains(x,x::ys) ==> fold(f,z,x::ys) == fold(f,z,x::x::ys)
                  val eqLemma: Theorem = implI(x === y){ equality => 
                    fold(f,z,ConsA(y,ys))                               ==|
                                                                 equality |
                    fold(f,z,ConsA(x,ys))                               ==|
                                                                  trivial |
                    fold(f,f(z,x),ys)                                   ==|
                                              forallE(isIdempotentf)(z,x) |
                    fold(f,f(f(z,x),x),ys)                              ==|
                                                                  trivial |
                    fold(f,f(z,x),ConsA(x,ys))                          ==|
                                                                  trivial |
                    fold(f,z,ConsA(x,ConsA(x,ys)))
                  }       
                  // contains(x,y::ys) ==> fold(f,z,y::ys) == fold(f,z,x::y::ys)        
                  val neqLemma: Theorem = implI(x !== y){ nequality => 
                    fold(f,z,ConsA(x,ConsA(y,ys)))                                                            ==|
                                                                                                        trivial |
                    fold(f,f(z,x),ConsA(y,ys))                                                                ==|
                                                                                                        trivial |
                    fold(f,f(f(z,x),y),ys)                                                                    ==|
                                                                              forallE(isCommutativef)(f(z,x),y) |
                    fold(f,f(y,f(z,x)),ys)                                                                    ==|
                                                                                     forallE(isAssocf)(y,z,x)   |
                    fold(f,f(f(y,z),x),ys)                                                                    ==|
                                                                                                        trivial |
                    fold(f,f(y,z),ConsA(x,ys))                                                                ==|
                                                                                   forallE(isCommutativef)(y,z) |
                    fold(f,f(z,y),ConsA(x,ys))                                                                ==|
                      andI(forallE(ihs.hypothesis(ys))(x,f(z,y)),forallE(lemma2)(x,y,ys), nequality, containsx) |
                    fold(f,f(z,y),ys)                                                                         ==|
                                                                                                        trivial |
                    fold(f,z,ConsA(y,ys))
                  }
                 andI(eqLemma,neqLemma)
                case C(`nil`) => truth
              }
            }    
          }
        }  

        

        def finalProperty(l1: Expr) = {
          forall("z"::A,"l2"::ListA){case (z,l2) => 
            {content(l1) === content(l2)} ==> {fold(f,z,l1) === fold(f,z,l2)}
          }
        }

        structuralInduction(finalProperty _,"l1"::ListA) { case (ihs, goal) =>
          forallI("z"::A,"l2"::ListA) { case (z,l2) =>
            implI(content(ihs.expression) === content(l2)){ contents =>
              ihs.expression match {
                case C(`cons`, x, xs) =>
                  val xsContainsX: Theorem = implI(content(xs).contains(x)){ contains =>
                    fold(f,z,ConsA(x,xs))                                   ==|
                                                                      trivial |
                    fold(f,f(z,x),xs)                                       ==|
                                   andI(forallE(ihs.hypothesis(xs))(f(z,x),l2),
                                  forallE(lemma7)(x,xs,l2),contents,contains) |
                    fold(f,f(z,x),l2)                                       ==|
                                                                      trivial |
                    fold(f,z,ConsA(x,l2))                                   ==|
                                        andI(forallE(forallE(lemma3)(l2))(x,z),
                                             contents,forallE(lemma4)(x,l1,l2),
                                                       forallE(lemma9)(x,xs)) |
                    fold(f,z,l2)
                  }

                  val xsNotContainsX: Theorem = implI(Not(content(xs).contains(x))){ ncontains =>
                    fold(f,z,ConsA(x,xs))                                            ==|
                                                                               trivial |
                    fold(f,f(z,x),xs)                                                ==|
                                 andI(forallE(ihs.hypothesis(xs))(f(z,x),without(x,l2)),
                                           forallE(lemma8)(x,xs,l2),contents, ncontains,
                                                      forallE(forallE(lemma5)(l2))(x)) |
                    fold(f,f(z,x),without(x,l2))                                     ==|
                                                                               trivial |
                    fold(f,z,ConsA(x,without(x,l2)))                                 ==|
                                                     forallE(forallE(lemma1)(l2))(x,z) |
                    fold(f,z,ConsA(x,l2))                                            ==|
                                                 andI(forallE(forallE(lemma3)(l2))(x,z),
                                                      contents,forallE(lemma4)(x,l1,l2),
                                                                forallE(lemma9)(x,xs)) |
                    fold(f,z,l2)             
                  }
                  andI(xsContainsX, xsNotContainsX)
                case C(`nil`) => contents
              }
            }  
          }
        }       
      }    
    } 

    lazy val theorem: Theorem = prove(
      forall("f" :: ((A, A) =>: A), "z" :: A, "l1" :: ListA, "l2" :: ListA) { case (f,z,l1,l2) => 
        ( isAssociative(f) && 
          isCommutative(f) &&
          isIdempotent(f) && 
          content(l1) === content(l2)) ==> {fold(f,z,l1) === fold(f,z,l2)} 
      },
      equivalent
    )
}