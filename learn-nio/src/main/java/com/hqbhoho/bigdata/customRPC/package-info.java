package com.hqbhoho.bigdata.customRPC;
/*
*
* 手写RPC框架
*
* 序列化工具：由于需要网络通信，所以对象需要可以序列化和反序列化。
*           常用的一般有Gson、Jackson等，我们咱们就要原生方法吧。
* 动态代理：由于需要在消费者端执行接口，然后此时可以直接获取到服务提供端的具体实现类的结果，
*         这就需要使用JDK的动态代理方法了。
* 网络通信：在动态代理的实现过程中肯定是需要进行网络通信获取相应的服务结果，
*          这里可以使用Java BIO中的socket或者像Dubbo那样使用Netty。
* Java反射：消费端在网络请求的时候肯定会将类、方法、参数类型、参数值这些传过来的，
*           在服务提供端可以通过反射机制直接执行实现类，然后返回结果。
* 服务注册中心：当然了在大面积微服务的框架下，服务的提供者在哪些网络节点中肯定不是写死在代码中的，
*            这时候就需要一个配置中心去读取所有的服务提供者。一般可以使用Zookeeper、Redis或者Nacos来作为服务注册与发现中心。在本文章中，为了方便起见咱们就直接写死地址了，没有使用注册中心。
* 负载均衡：如上所说，服务的提供者可能会有好多，
*           所以每次的请求需要去哪一个服务提供者中去调用也是不确定，
*           所有的请求都去同一个提供者那势必会造成网络阻塞的，
*           所以负载均衡算法也是很有必要的。
*           但是同上，为了简化咱们的RPC框架，咱们不会去实现负载均衡的功能。
*
* 示例  服务发现使用zookeeper   网络通信 使用 Netty   序列化使用 MarshallingCodeC
* 客户端测试类
* {@link com.hqbhoho.bigdata.customRPC.test.ConsumerTest}
* 服务端测试类
* {@link com.hqbhoho.bigdata.customRPC.test.ProviderTest}
*
*
* @author hqbhoho
* @version [v1.0]
* @date 2019/12/10
*/