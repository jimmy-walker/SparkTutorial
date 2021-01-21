package com.jimmy.test;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.dependency.IDependencyParser;
import com.hankcs.hanlp.dependency.perceptron.parser.KBeamArcEagerDependencyParser;
import com.hankcs.hanlp.dictionary.CoreDictionary;
import com.hankcs.hanlp.utility.Predefine;

import java.io.IOException;

public class Teststatic {
    public static void main(String[] args) throws IOException, ClassNotFoundException
    {
        HanLP.Config.enableDebug();         // 为了避免你等得无聊，开启调试模式说点什么:-)
//        System.out.printf(HanLP.parseDependency("2019年抖音最火背景音乐").toString());
        IDependencyParser parser = new KBeamArcEagerDependencyParser();
        System.out.printf(parser.parse("2019年抖音最火背景音乐").toString());
        System.out.printf(parser.parse("一人一首代表作·华语流行珍藏篇").toString());
//        new CoreDictionary.Attribute(Nature.begin, Predefine.MAX_FREQUENCY / 10);
////        new CoreDictionary();
//        CoreDictionary.getWordID(Predefine.TAG_BIGIN);
    }
}
